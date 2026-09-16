import pytest

from dbtower_aiops.dbtower import DBTowerClient, DBTowerError
from dbtower_aiops.graph import build_graph, case_document
from dbtower_aiops.knowledge import Hit
from dbtower_aiops.notify import Notifier
from dbtower_aiops.slack import SlackClient

from .conftest import MemoryLeases, Recorder


class FakeRetriever:
    def __init__(self):
        self.queries = []

    def search(self, query, team, job_type, limit=3, dbms=None):
        self.queries.append((query, team, job_type, dbms))
        return [Hit("runbook:operations.md#1", "runbook", "백업 점검", "백업이 없으면 정책부터 본다", 0.8, 0.3, 1.2)]

    def upsert(self, documents):
        return len(documents)


def setup(fake, retriever=None):
    rec = Recorder()
    client = DBTowerClient("http://dbtower.test", "token", transport=fake.transport())
    notifier = Notifier(SlackClient("xoxb", "https://slack.test/api", transport=rec.transport()), "http://dbtower.test")
    leases = MemoryLeases()
    return build_graph(client, notifier, leases, "w1", retriever), leases, rec


@pytest.mark.asyncio
async def test_선점부터_알림까지_한_번에_간다(fake_dbtower):
    job_id = fake_dbtower.add_job("BACKUP_RISK_REVIEW")
    retriever = FakeRetriever()
    graph, _, rec = setup(fake_dbtower, retriever)

    state = await graph.ainvoke({"job_id": job_id, "attempt": 1})

    assert state["outcome"] == "completed"
    assert [c for c in fake_dbtower.calls] == ["POST claim", "POST facts", "POST retrieving", "POST analyze",
                                               "POST notified"]
    # 인스턴스 이름은 질의에서 빠지고, 팀 범위·유형·기종이 조건으로 넘어간다
    assert retriever.queries == [("느려요 백업 없음", "team-a", "BACKUP_RISK_REVIEW", "POSTGRESQL")]
    assert fake_dbtower.analyze_body["references"][0]["id"] == "runbook:operations.md#1"
    assert rec.bodies("chat.postMessage")[0]["channel"] == "C1"


@pytest.mark.asyncio
async def test_분석_중_일시_오류가_나면_다시_배달됐을_때_선점_없이_이어간다(fake_dbtower):
    job_id = fake_dbtower.add_job()
    graph, leases, rec = setup(fake_dbtower)
    fake_dbtower.fail_next["analyze"] = [503]

    with pytest.raises(DBTowerError) as first:
        await graph.ainvoke({"job_id": job_id, "attempt": 1})
    assert first.value.transient
    assert fake_dbtower.jobs[job_id]["status"] == "COLLECTING"

    fake_dbtower.calls.clear()
    state = await graph.ainvoke({"job_id": job_id, "attempt": 1})

    assert state["outcome"] == "completed"
    assert "POST claim" not in fake_dbtower.calls
    assert fake_dbtower.calls[0] == "GET lease-view"
    assert len(rec.bodies("chat.postMessage")) == 1


@pytest.mark.asyncio
async def test_알림을_이미_보낸_작업이_다시_배달되면_Slack에_두_번_쓰지_않는다(fake_dbtower):
    job_id = fake_dbtower.add_job()
    graph, _, rec = setup(fake_dbtower)
    await graph.ainvoke({"job_id": job_id, "attempt": 1})
    await graph.ainvoke({"job_id": job_id, "attempt": 1})
    assert len(rec.bodies("chat.postMessage")) == 1


@pytest.mark.asyncio
async def test_다른_실행기가_가져간_작업은_건드리지_않는다(fake_dbtower):
    job_id = fake_dbtower.add_job(status="COLLECTING", lease="someone-else")
    graph, _, rec = setup(fake_dbtower)
    state = await graph.ainvoke({"job_id": job_id, "attempt": 1})
    assert state["outcome"] == "claimed_elsewhere"
    assert fake_dbtower.calls == ["POST claim"] and not rec.requests


@pytest.mark.asyncio
async def test_재시도로_바뀐_시도의_옛_메시지는_그냥_끝난다(fake_dbtower):
    job_id = fake_dbtower.add_job(status="COLLECTING", lease="attempt-2-token")
    graph, leases, _ = setup(fake_dbtower)
    await leases.put(job_id, 1, "attempt-1-token")
    state = await graph.ainvoke({"job_id": job_id, "attempt": 1})
    assert state["outcome"] == "stale_attempt"


def test_검증_안_된_소견은_과거_사례로_남기지_않는다():
    base = {"jobId": "j", "type": "QUERY_DIAGNOSIS", "prompt": "p", "scopeTeam": "team-a"}
    assert case_document({**base, "result": {"aiOpinion": "x", "unverifiedClaims": ["사실 목록에 없는 수치: 9"]}}) is None
    doc = case_document({**base, "result": {"aiOpinion": "x", "unverifiedClaims": [], "ruleFindings": ["g"]}})
    assert doc.team == "team-a" and doc.job_types == ("QUERY_DIAGNOSIS",)


def test_검색_질의에서_인스턴스_이름을_뺀다():
    from dbtower_aiops.graph import retrieval_query
    query = retrieval_query("live-mysql-team-a 최근 1시간 느려진 쿼리 봐줘",
                            ["[live-mysql-team-a] 헬스 스코어 44점으로 60점 미만입니다"],
                            ["[live-mysql-team-a] 기종 MYSQL, 팀 team-a"])
    assert "live-mysql-team-a" not in query and "mysql" not in query.lower()
    assert query == "최근 1시간 느려진 쿼리 봐줘 헬스 스코어 44점으로 60점 미만입니다"
