"""Slack 서명 검증, 메시지 전송, 결과 문안.

결과 문안은 사실(DBTower가 모은 것)과 AI 소견(모델이 만든 것)을 섞지 않는다. 검증 안 된 수치가 있으면
소견보다 먼저 경고를 보여준다 — 스레드에서 소견만 읽고 지나가는 사람이 확인 안 된 수치를 사실로 믿지 않게.
"""
from __future__ import annotations

import hashlib
import hmac
import time
from typing import Any

import httpx

REPLAY_WINDOW_S = 300
_TYPE_LABEL = {
    "QUERY_DIAGNOSIS": "쿼리 진단", "REGRESSION_EXPLANATION": "회귀 원인", "BACKUP_RISK_REVIEW": "백업 위험",
    "SLO_RISK_REVIEW": "SLO 위험", "ADVISOR_SUMMARY": "Advisor 요약", "COST_REVIEW": "비용 검토",
    "INCIDENT_TRIAGE": "장애 초기 진단", "DB_TEAM_INQUIRY": "DB팀 문의", "PERIODIC_REPORT": "정기 리포트",
}


def verify_signature(secret: str, timestamp: str | None, body: bytes, signature: str | None,
                     now: float | None = None) -> bool:
    if not secret or not timestamp or not signature:
        return False
    try:
        ts = int(timestamp)
    except ValueError:
        return False
    if abs((now if now is not None else time.time()) - ts) > REPLAY_WINDOW_S:
        return False
    base = b"v0:" + timestamp.encode() + b":" + body
    expected = "v0=" + hmac.new(secret.encode(), base, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)


def type_label(type_name: str) -> str:
    return _TYPE_LABEL.get(type_name, type_name)


def _escape(text: str) -> str:
    # Slack mrkdwn의 제어 문자 — 사실에 섞인 쿼리 텍스트가 멘션·링크로 해석되지 않게 한다
    return (text or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _bullets(items: list[str], limit: int) -> str:
    shown = [f"- {_escape(i)}" for i in items[:limit]]
    if len(items) > limit:
        shown.append(f"- 외 {len(items) - limit}건은 DBTower에서 확인")
    return "\n".join(shown)


def accepted_text(job: dict[str, Any], instance_name: str | None) -> str:
    target = instance_name or "범위 안 전체 인스턴스"
    return (f"접수했습니다. {type_label(job['type'])} / {target}\n"
            f"작업 {job['jobId'][:8]} - 사실을 모으고 분석하면 이 스레드에 결과를 붙입니다.")


def job_link(console_url: str, job_id: str) -> str:
    """웹 콘솔의 AI 운영 작업 카드로 보내는 링크. JSON API를 가리키면 스레드에서 눌러도 사람이 읽을 화면이 없다."""
    return f"{console_url}/?aiop={job_id}"


def result_text(job: dict[str, Any], console_url: str) -> str:
    link = job_link(console_url, job["jobId"])
    title = f"[{type_label(job['type'])}] 작업 {job['jobId'][:8]}"
    if job.get("status") != "COMPLETED":
        return f"{title} 실패: {_escape(job.get('failureReason') or job.get('status'))}\n상세: {link}"
    result = job.get("result") or {}
    parts = [title]
    unverified = result.get("unverifiedClaims") or []
    if unverified:
        parts.append("*주의* AI 소견에 사실 목록과 대조되지 않은 내용이 있습니다:\n" + _bullets(unverified, 5))
    rules = result.get("ruleFindings") or []
    parts.append("*규칙 판정*\n" + (_bullets(rules, 6) if rules else "- 규칙에 걸린 항목 없음"))
    if result.get("aiOpinion"):
        parts.append("*AI 1차 소견* (판단은 사람이 합니다)\n" + _escape(result["aiOpinion"]))
    else:
        parts.append("*AI 1차 소견* 없음 - 규칙 판정만 제공합니다")
    uncertainties = result.get("uncertainties") or []
    if uncertainties:
        parts.append("*불확실한 점*\n" + _bullets(uncertainties, 4))
    actions = result.get("nextActions") or []
    if actions:
        parts.append("*다음 조치*\n" + _bullets(actions, 5))
    if result.get("approvalRequired"):
        # 단정하지 않는다 — 조치 목록의 "필요하면 인덱스 추가를 검토" 같은 조건부 문장도 규칙에 걸려 승인 대상이 된다(169절)
        parts.append("대상 DB를 바꿀 수 있는 조치가 언급됐습니다. 실행 전 워크벤치 변경 요청으로 승인을 받으세요.")
    parts.append(f"사실 {len(result.get('facts') or [])}건과 근거 전체: {link}")
    return "\n\n".join(parts)


class SlackClient:
    def __init__(self, token: str, api_base: str = "https://slack.com/api", *,
                 transport: httpx.AsyncBaseTransport | None = None):
        self._token = token
        self._http = httpx.AsyncClient(base_url=api_base, timeout=10.0, transport=transport)

    @property
    def enabled(self) -> bool:
        return bool(self._token)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def post_thread(self, channel: str, thread_ts: str | None, text: str) -> dict[str, Any]:
        if not self.enabled:
            return {"ok": False, "error": "bot_token_missing"}
        payload: dict[str, Any] = {"channel": channel, "text": text}
        if thread_ts:
            payload["thread_ts"] = thread_ts
        response = await self._http.post("/chat.postMessage", json=payload,
                                         headers={"Authorization": f"Bearer {self._token}"})
        response.raise_for_status()
        body = response.json()
        if not body.get("ok"):
            # Slack은 실패도 200으로 준다 — ok=false를 예외로 올려야 재시도·실패 기록이 된다
            raise RuntimeError(f"Slack chat.postMessage 실패: {body.get('error')}")
        return body
