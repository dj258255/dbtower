"""AI 운영 작업 한 건의 실행 그래프 (LangGraph).

자유 에이전트가 아니라 고정된 그래프다. 모델이 다음 단계나 도구를 고르지 않는다 — 단계와 순서는 코드가 정하고,
모델 호출은 analyze 노드 안에서 DBTower를 거쳐 한 번만 일어난다.

    start ─┬─> claim ──> collect ──> retrieve ──> analyze ──> notify ──> END
           ├─> collect / retrieve / analyze / notify   (재배달 — 저장된 리스로 현재 단계부터 이어간다)
           └─> END                                      (이미 끝났거나 다른 실행기의 작업)
"""
from __future__ import annotations

import asyncio
import re
from typing import Any, Protocol, TypedDict

from langgraph.graph import END, StateGraph

from .dbtower import DBTowerClient, DBTowerError
from .knowledge import Document
from .notify import Notifier


class LeaseStore(Protocol):
    async def get(self, job_id: str, attempt: int) -> str | None: ...

    async def put(self, job_id: str, attempt: int, token: str) -> None: ...


class Retriever(Protocol):
    def search(self, query: str, team: str | None, job_type: str, limit: int = 3,
               dbms: str | None = None) -> list[Any]: ...

    def upsert(self, documents: list[Document]) -> int: ...


class JobState(TypedDict, total=False):
    job_id: str
    attempt: int
    lease: str
    job: dict[str, Any]
    facts: dict[str, Any]
    references: list[dict[str, Any]]
    outcome: str
    next: str


# 정기 리포트는 여러 인스턴스를 훑는 요약이라 한 사례·런북을 붙이면 오히려 초점이 흐려진다
_RETRIEVAL_SKIP = {"PERIODIC_REPORT"}


def build_graph(client: DBTowerClient, notifier: Notifier, leases: LeaseStore, worker_id: str,
                retriever: Retriever | None = None, analyze_timeout_s: float = 240.0,
                collect_timeout_s: float = 180.0, ingest_cases: bool = False):

    async def start(state: JobState) -> JobState:
        token = await leases.get(state["job_id"], state["attempt"])
        if not token:
            return {"next": "claim"}
        try:
            job = await client.lease_view(state["job_id"], token)
        except DBTowerError as exc:
            if exc.conflict:
                # 재시도로 토큰이 바뀌었다 — 이 메시지는 옛 시도의 것이다
                return {"next": END, "outcome": "stale_attempt"}
            raise
        step = {
            "AUTHORIZED": "collect", "COLLECTING": "collect", "RETRIEVING": "retrieve",
            "ANALYZING": "analyze", "VERIFYING": "analyze", "COMPLETED": "notify", "FAILED": "notify",
        }.get(job["status"], END)
        facts = job.get("result") or {}
        return {"lease": token, "job": job, "facts": facts, "next": step,
                "outcome": "cancelled" if job["status"] == "CANCELLED" else state.get("outcome", "")}

    async def claim(state: JobState) -> JobState:
        try:
            claimed = await client.claim(state["job_id"], worker_id)
        except DBTowerError as exc:
            body = exc.body if isinstance(exc.body, dict) else {}
            if exc.conflict and body.get("leaseToken") and (body.get("job") or {}).get("status") == "FAILED":
                # 범위 확인 실패 — 알림 전용 토큰으로 요청자에게 실패를 알린다
                await leases.put(state["job_id"], state["attempt"], body["leaseToken"])
                return {"lease": body["leaseToken"], "job": body["job"], "next": "notify"}
            if exc.conflict:
                return {"next": END, "outcome": "claimed_elsewhere"}
            raise
        await leases.put(state["job_id"], state["attempt"], claimed["leaseToken"])
        return {"lease": claimed["leaseToken"], "job": claimed["job"], "next": "collect"}

    async def collect(state: JobState) -> JobState:
        facts = await client.facts(state["job_id"], state["lease"], collect_timeout_s)
        return {"facts": facts, "job": facts["job"],
                "next": "analyze" if retriever is None or facts["job"]["type"] in _RETRIEVAL_SKIP else "retrieve"}

    async def retrieve(state: JobState) -> JobState:
        job = (await client.retrieving(state["job_id"], state["lease"]))
        facts = state.get("facts") or {}
        query = retrieval_query(job.get("prompt") or "", list(facts.get("ruleFindings") or []), list(facts.get("facts") or []))
        # 임베딩과 DB 조회는 동기 호출이다 — 이벤트 루프를 막으면 같은 실행기의 다른 작업이 멈춘다
        hits = await asyncio.to_thread(retriever.search, query, job.get("scopeTeam"), job["type"], 3,
                                       job.get("instanceType")) if retriever else []
        return {"job": job, "references": [h.as_reference() for h in hits], "next": "analyze"}

    async def analyze(state: JobState) -> JobState:
        job = await client.analyze(state["job_id"], state["lease"], state.get("references") or [],
                                   timeout=analyze_timeout_s)
        return {"job": job, "next": "notify"}

    async def notify(state: JobState) -> JobState:
        job = state["job"]
        if job.get("notifiedAt"):
            return {"next": END, "outcome": job["status"].lower()}
        # 알림 문안에는 결과가 필요하다 — 재배달로 들어온 경우 lease-view가 결과까지 준다
        if job["status"] == "COMPLETED" and not job.get("result"):
            job = await client.lease_view(state["job_id"], state["lease"])
        await notifier.send(job)
        marked = await client.notified(state["job_id"], state["lease"])
        if ingest_cases and retriever and job["status"] == "COMPLETED":
            document = case_document(job)
            if document:
                await asyncio.to_thread(retriever.upsert, [document])
        return {"job": marked["job"], "next": END, "outcome": job["status"].lower()}

    graph = StateGraph(JobState)
    for name, fn in (("start", start), ("claim", claim), ("collect", collect), ("retrieve", retrieve),
                     ("analyze", analyze), ("notify", notify)):
        graph.add_node(name, fn)
    graph.set_entry_point("start")
    routes = {"claim": "claim", "collect": "collect", "retrieve": "retrieve", "analyze": "analyze",
              "notify": "notify", END: END}
    for name in ("start", "claim", "collect", "retrieve", "analyze", "notify"):
        graph.add_conditional_edges(name, lambda s: s.get("next", END), routes)
    return graph.compile()


_BRACKET = re.compile(r"\[([^\]]+)\]")


def retrieval_query(prompt: str, rule_findings: list[str], facts: list[str]) -> str:
    """검색 질의 — 요청 문장과 규칙 판정(무엇이 걸렸나)을 합치되 인스턴스 이름은 뺀다.

    이름은 주제가 아니라 식별자다. 실측(169절)에서 "live-mysql-team-a"의 mysql이 어휘 점수를 끌어올려 최소권한 문서의
    MySQL 권한표가 느린 쿼리 질의의 참고 자료로 붙었다. 이름을 빼자 1순위 적중이 3/9에서 5/9로 올랐다.
    """
    names = {m.group(1) for line in facts + rule_findings for m in _BRACKET.finditer(line)}
    text = " ".join([prompt] + rule_findings[:5])
    for name in sorted(names, key=len, reverse=True):
        text = text.replace(f"[{name}]", " ").replace(name, " ")
    return re.sub(r"\s+", " ", text).strip()


def case_document(job: dict[str, Any]) -> Document | None:
    """끝난 작업을 다음 검색의 과거 사례로 만든다. 검증 안 된 수치가 있는 소견은 사례로 남기지 않는다.

    기본은 꺼져 있다(KNOWLEDGE_INGEST_CASES). 켜면 AI 소견이 다음 작업의 참고 자료가 되어, 사람이 고르지 않은
    모델 문장이 스스로를 강화할 수 있다 — 사람이 검토한 사례만 넣는 흐름이 생기기 전까지는 운영자가 판단해 켠다.
    """
    result = job.get("result") or {}
    if not result.get("aiOpinion") or result.get("unverifiedClaims"):
        return None
    content = "\n".join(["요청: " + (job.get("prompt") or ""), "규칙 판정: " + "; ".join(result.get("ruleFindings") or []),
                         "소견: " + result["aiOpinion"], "조치: " + "; ".join(result.get("nextActions") or [])])
    return Document(f"case:{job['jobId']}", "case", f"{job['type']} 과거 사례", content[:1500],
                    job.get("scopeTeam"), (job["type"],), job.get("instanceType"))
