"""작업이 끝났을 때의 알림 — Slack 스레드와 n8n 웹훅.

n8n에는 판단을 넘기지 않는다. 결과의 요약 필드만 서명해서 보내고, 티켓 생성·온콜 호출·메일 같은
외부 연동 흐름만 n8n이 맡는다. 승인 필요 여부의 권위는 DBTower 결과이고 n8n은 그 값을 읽기만 한다.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import time
from typing import Any

import httpx

from .slack import SlackClient, job_link, result_text


def n8n_payload(job: dict[str, Any], console_url: str) -> dict[str, Any]:
    result = job.get("result") or {}
    return {
        "event": "ai_operation.finished",
        "jobId": job["jobId"],
        "type": job["type"],
        "status": job["status"],
        "trigger": job.get("trigger"),
        "requester": job.get("requester"),
        "scopeTeam": job.get("scopeTeam"),
        "instanceId": job.get("instanceId"),
        "approvalRequired": bool(result.get("approvalRequired")),
        "ruleFindings": result.get("ruleFindings") or [],
        "unverifiedClaimCount": len(result.get("unverifiedClaims") or []),
        "failureReason": job.get("failureReason"),
        # 요청 문장·사실 원문·소견 전문은 싣지 않는다 — 외부 자동화 도구의 실행 이력에 운영 데이터가 쌓이지 않게. 필요하면 링크로 본다
        "url": job_link(console_url, job["jobId"]),
    }


def sign(secret: str, timestamp: str, body: bytes) -> str:
    return "sha256=" + hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()


class Notifier:
    def __init__(self, slack: SlackClient, console_url: str, n8n_url: str = "", n8n_secret: str = "", *,
                 transport: httpx.AsyncBaseTransport | None = None):
        self._slack = slack
        self._console_url = console_url
        self._n8n_url = n8n_url
        self._n8n_secret = n8n_secret
        self._http = httpx.AsyncClient(timeout=10.0, transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def send(self, job: dict[str, Any]) -> list[str]:
        """보낸 채널 목록을 돌려준다. 한 채널의 실패가 다른 채널을 막지 않되, 실패는 예외로 모아 올린다."""
        sent: list[str] = []
        errors: list[str] = []
        if job.get("replyChannel") and self._slack.enabled:
            try:
                await self._slack.post_thread(job["replyChannel"], job.get("replyThread"),
                                              result_text(job, self._console_url))
                sent.append("slack")
            except Exception as exc:  # noqa: BLE001 - 채널별 실패를 모아 한 번에 올린다
                errors.append(f"slack: {exc}")
        if self._n8n_url:
            body = json.dumps(n8n_payload(job, self._console_url), ensure_ascii=False).encode()
            timestamp = str(int(time.time()))
            headers = {"Content-Type": "application/json", "X-DBTower-Timestamp": timestamp}
            if self._n8n_secret:
                headers["X-DBTower-Signature"] = sign(self._n8n_secret, timestamp, body)
            try:
                response = await self._http.post(self._n8n_url, content=body, headers=headers)
                response.raise_for_status()
                sent.append("n8n")
            except Exception as exc:  # noqa: BLE001
                errors.append(f"n8n: {exc}")
        if errors:
            raise NotificationError(sent, errors)
        return sent


class NotificationError(Exception):
    def __init__(self, sent: list[str], errors: list[str]):
        super().__init__("; ".join(errors))
        self.sent = sent
        self.errors = errors
