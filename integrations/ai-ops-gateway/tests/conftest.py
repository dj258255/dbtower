"""테스트 공용 가짜 — DBTower 작업 API의 전이 규칙을 작게 흉내 낸다.

흉내 내는 규칙은 Java 쪽 계약과 같다: 선점은 RECEIVED에서만, 이후 단계는 리스 토큰이 맞아야, 같은 단계 재진입 허용,
끝난 작업은 409. 실제 DBTower와의 관통은 docs/VERIFICATION.md 169절에 따로 기록한다.
"""
from __future__ import annotations

import json
import os
import uuid
from collections import defaultdict
from typing import Any

import httpx
import pytest

ORDER = ["RECEIVED", "AUTHORIZED", "COLLECTING", "RETRIEVING", "ANALYZING", "VERIFYING", "COMPLETED"]


class FakeDBTower:
    def __init__(self) -> None:
        self.jobs: dict[str, dict[str, Any]] = {}
        self.calls: list[str] = []
        self.fail_next: dict[str, list[int]] = defaultdict(list)  # 경로 끝 이름 -> 돌려줄 상태 코드 목록
        self.outbox: list[dict[str, Any]] = []
        self.instances = [
            {"id": 1, "name": "orders-db", "type": "POSTGRESQL", "teamLabel": "team-a"},
            {"id": 2, "name": "orders", "type": "MYSQL", "teamLabel": "team-a"},
            {"id": 3, "name": "billing-db", "type": "MYSQL", "teamLabel": "team-b"},
        ]
        self.submitted: list[dict[str, Any]] = []
        self.analyze_body: dict[str, Any] | None = None

    def add_job(self, job_type: str = "QUERY_DIAGNOSIS", status: str = "RECEIVED", **extra: Any) -> str:
        job_id = str(uuid.uuid4())
        self.jobs[job_id] = {"jobId": job_id, "type": job_type, "status": status, "prompt": "orders-db 느려요",
                             "scopeTeam": "team-a", "instanceType": "POSTGRESQL", "replyChannel": "C1", "replyThread": "171.1",
                             "notifiedAt": None, "lease": None, "result": None, "failureReason": None, **extra}
        return job_id

    def view(self, job: dict[str, Any]) -> dict[str, Any]:
        return {k: v for k, v in job.items() if k != "lease"}

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        name = path.rsplit("/", 1)[-1]
        self.calls.append(f"{request.method} {name}")
        if self.fail_next[name]:
            return httpx.Response(self.fail_next[name].pop(0), json={"message": "주입한 오류"})
        body = json.loads(request.content) if request.content else {}
        lease = request.headers.get("X-Lease-Token")
        if path == "/api/ai-operations" and request.method == "POST":
            self.submitted.append(body)
            job_id = self.add_job(body["type"])
            return httpx.Response(202, json=self.view(self.jobs[job_id]))
        if path == "/api/instances":
            return httpx.Response(200, json=self.instances)
        if name == "claim" and "outbox" in path:
            claimed, self.outbox = self.outbox, []
            return httpx.Response(200, json=claimed)
        if name == "published":
            return httpx.Response(200, json={"published": True})
        job = self.jobs[path.split("/")[3]]
        if name == "claim":
            if job["status"] != "RECEIVED":
                return httpx.Response(409, json={"message": "허용되지 않는 상태 전이"})
            job.update(status="AUTHORIZED", lease=str(uuid.uuid4()))
            return httpx.Response(200, json={"job": self.view(job), "leaseToken": job["lease"]})
        if job["lease"] != lease:
            return httpx.Response(409, json={"message": "리스를 쥔 실행기가 아닙니다"})
        if name in ("facts", "retrieving", "analyze"):
            target = {"facts": "COLLECTING", "retrieving": "RETRIEVING", "analyze": "ANALYZING"}[name]
            if ORDER.index(job["status"]) > ORDER.index(target) or job["status"] in ("FAILED", "CANCELLED"):
                return httpx.Response(409, json={"message": "되돌릴 수 없음"})
            job["status"] = target
            if name == "facts":
                job["result"] = {"facts": ["[orders-db] 헬스 스코어 55점"], "ruleFindings": ["[orders-db] 백업 없음"]}
                return httpx.Response(200, json={"job": self.view(job), "facts": job["result"]["facts"],
                                                 "ruleFindings": job["result"]["ruleFindings"], "uncertainties": []})
            if name == "analyze":
                self.analyze_body = body
                job["status"] = "COMPLETED"
                job["result"] = {**job["result"], "aiOpinion": "백업이 없다", "unverifiedClaims": [],
                                 "nextActions": ["백업 정책 확인"], "approvalRequired": False}
            return httpx.Response(200, json=self.view(job))
        if name == "lease-view":
            return httpx.Response(200, json=self.view(job))
        if name == "notified":
            already = job["notifiedAt"] is not None
            job["notifiedAt"] = job["notifiedAt"] or "2026-09-16T00:00:00Z"
            return httpx.Response(200, json={"job": self.view(job), "alreadyNotified": already})
        if name == "fail":
            job.update(status="FAILED", failureReason=body.get("reason"))
            return httpx.Response(200, json=self.view(job))
        return httpx.Response(404)


class MemoryLeases:
    def __init__(self) -> None:
        self.tokens: dict[tuple[str, int], str] = {}

    async def get(self, job_id: str, attempt: int) -> str | None:
        return self.tokens.get((job_id, attempt))

    async def put(self, job_id: str, attempt: int, token: str) -> None:
        self.tokens[(job_id, attempt)] = token


class Recorder:
    """Slack·n8n 요청을 받아 적는 가짜 외부 서비스."""

    def __init__(self, slack_ok: bool = True) -> None:
        self.requests: list[httpx.Request] = []
        self.slack_ok = slack_ok

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            self.requests.append(request)
            if request.url.path.endswith("chat.postMessage"):
                return httpx.Response(200, json={"ok": self.slack_ok, "error": None if self.slack_ok else "channel_not_found"})
            return httpx.Response(200, json={"received": True})
        return httpx.MockTransport(handle)

    def bodies(self, suffix: str) -> list[dict[str, Any]]:
        return [json.loads(r.content) for r in self.requests if r.url.path.endswith(suffix)]


@pytest.fixture
def fake_dbtower() -> FakeDBTower:
    return FakeDBTower()


def redis_url() -> str:
    return os.environ.get("TEST_REDIS_URL", "redis://localhost:16379/15")


def knowledge_dsn() -> str:
    return os.environ.get("TEST_KNOWLEDGE_DSN", "postgresql://knowledge:knowledge@localhost:15434/knowledge_test")
