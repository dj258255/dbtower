"""DBTower AI 운영 작업 API 클라이언트.

실행면은 DBTower의 상태를 직접 바꾸지 않는다. 모든 전이는 이 클라이언트가 부르는 REST를 거치고,
선점 뒤 단계는 X-Lease-Token이 맞아야 DBTower가 받아준다.
"""
from __future__ import annotations

from typing import Any

import httpx


class DBTowerError(Exception):
    def __init__(self, status: int, message: str, body: Any = None):
        super().__init__(f"DBTower {status}: {message}")
        self.status = status
        self.message = message
        self.body = body

    @property
    def conflict(self) -> bool:
        """409 — 이미 다른 실행기가 가져갔거나 작업 상태가 바뀌었다. 재시도해도 결과가 같다."""
        return self.status == 409

    @property
    def transient(self) -> bool:
        """재시도할 가치가 있는 실패 — 5xx(DBTower 일시 장애)와 429."""
        return self.status >= 500 or self.status == 429


class DBTowerClient:
    def __init__(self, base_url: str, token: str, *, transport: httpx.AsyncBaseTransport | None = None,
                 timeout: float = 30.0):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self._http = httpx.AsyncClient(base_url=base_url, headers=headers, timeout=timeout, transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _call(self, method: str, path: str, *, lease: str | None = None, json: Any = None,
                    params: dict[str, Any] | None = None, timeout: float | None = None) -> Any:
        headers = {"X-Lease-Token": lease} if lease else None
        try:
            response = await self._http.request(method, path, json=json, params=params, headers=headers,
                                                timeout=timeout if timeout is not None else httpx.USE_CLIENT_DEFAULT)
        except httpx.TransportError as exc:
            # 연결 실패·시간 초과는 DBTower가 요청을 받았는지 모른다 — 일시 장애로 보고 재시도한다
            raise DBTowerError(503, f"전송 실패: {exc.__class__.__name__}") from exc
        body: Any
        try:
            body = response.json() if response.content else None
        except ValueError:
            body = response.text
        if response.status_code >= 400:
            if isinstance(body, dict):
                message = body.get("message") or body.get("error") or body.get("detail") or ""
            else:
                message = str(body)[:200]
            raise DBTowerError(response.status_code, str(message), body)
        return body

    # ---- 게이트웨이 ----

    async def submit(self, request: dict[str, Any]) -> dict[str, Any]:
        return await self._call("POST", "/api/ai-operations", json=request)

    async def instances(self) -> list[dict[str, Any]]:
        return await self._call("GET", "/api/instances")

    # ---- 릴레이 ----

    async def claim_outbox(self, limit: int = 20) -> list[dict[str, Any]]:
        return await self._call("POST", "/api/ai-operations/outbox/claim", params={"limit": limit})

    async def mark_published(self, event_id: str, claim_token: str) -> bool:
        try:
            await self._call("POST", f"/api/ai-operations/outbox/{event_id}/published",
                             json={"claimToken": claim_token})
            return True
        except DBTowerError as exc:
            if exc.conflict:
                return False
            raise

    # ---- 실행기 ----

    async def claim(self, job_id: str, worker_id: str) -> dict[str, Any]:
        return await self._call("POST", f"/api/ai-operations/{job_id}/claim", json={"workerId": worker_id})

    async def facts(self, job_id: str, lease: str, timeout: float) -> dict[str, Any]:
        """사실 수집은 대상 DB를 직접 조회하는 단계라 호출자(그래프)가 제한을 정해 넘긴다 — 기본 30초로는 못 기다린다(170절 1번)"""
        return await self._call("POST", f"/api/ai-operations/{job_id}/facts", lease=lease, timeout=timeout)

    async def retrieving(self, job_id: str, lease: str) -> dict[str, Any]:
        return await self._call("POST", f"/api/ai-operations/{job_id}/retrieving", lease=lease)

    async def analyze(self, job_id: str, lease: str, references: list[dict[str, Any]],
                      timeout: float) -> dict[str, Any]:
        return await self._call("POST", f"/api/ai-operations/{job_id}/analyze", lease=lease,
                                json={"references": references}, timeout=timeout)

    async def fail(self, job_id: str, lease: str, reason: str) -> dict[str, Any]:
        return await self._call("POST", f"/api/ai-operations/{job_id}/fail", lease=lease,
                                json={"reason": reason[:500]})

    async def lease_view(self, job_id: str, lease: str) -> dict[str, Any]:
        return await self._call("GET", f"/api/ai-operations/{job_id}/lease-view", lease=lease)

    async def notified(self, job_id: str, lease: str) -> dict[str, Any]:
        return await self._call("POST", f"/api/ai-operations/{job_id}/notified", lease=lease)
