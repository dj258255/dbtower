"""배달 상한으로 닫는 작업의 실패 알림 — 170절 2번.

리퍼가 닫은 작업은 메시지가 재배달되며 그래프의 `start`가 `FAILED -> notify`로 보내 알림이 나간다. 그런데 배달
상한으로 죽은 작업은 `_bury`가 확인 처리(XACK)라 재배달이 없어 아무도 모른다 — 같은 FAILED인데 한쪽만 온콜에 닿았다.

여기서 보는 것은 스트림 동작이 아니라 "알림이 나가고도 묻는가"다. 그래서 Redis 없이 `handle()`만 부르고,
알림은 가짜가 아니라 **실제 그래프**를 지나 보낸다(워커가 문안을 새로 만드는지 잡으려면 그 경로여야 한다).
"""
import pytest

from dbtower_aiops.dbtower import DBTowerClient
from dbtower_aiops.graph import build_graph
from dbtower_aiops.notify import Notifier
from dbtower_aiops.slack import SlackClient
from dbtower_aiops.worker import Worker

from .conftest import MemoryLeases, Recorder


class FakeStreamRedis:
    """워커가 스트림에 하는 일만 흉내 낸다 — 배달 횟수는 우리가 정하고, 확인·격리는 받아 적는다."""

    def __init__(self, times_delivered: int = 2):
        self.times_delivered = times_delivered
        self.acked: list[str] = []
        self.dead: list[dict] = []

    async def xpending_range(self, stream, group, min, max, count):  # noqa: A002 - redis 클라이언트 인자 이름
        return [{"times_delivered": self.times_delivered}]

    async def xack(self, stream, group, message_id):
        self.acked.append(message_id)
        return 1

    async def xadd(self, stream, fields):
        self.dead.append(fields)
        return "1-0"


def make_worker(fake, redis, *, slack_ok: bool = True):
    rec = Recorder(slack_ok=slack_ok)
    leases = MemoryLeases()
    client = DBTowerClient("http://dbtower.test", "token", transport=fake.transport())
    notifier = Notifier(SlackClient("xoxb", "https://slack.test/api", transport=rec.transport()),
                        "http://dbtower.test")
    graph = build_graph(client, notifier, leases, "w1")
    worker = Worker(redis, client, graph, leases, stream="s", dead_letter_stream="s:dead",
                    group="g", consumer="c1", max_deliveries=2)
    return worker, leases, rec


@pytest.mark.asyncio
async def test_배달_상한에서_실패_알림을_한_번_보내고_그래도_묻는다(fake_dbtower):
    job_id = fake_dbtower.add_job(status="ANALYZING", lease="tok")
    redis = FakeStreamRedis(times_delivered=2)  # 상한(2)에 이미 이르렀다
    worker, leases, rec = make_worker(fake_dbtower, redis)
    await leases.put(job_id, 1, "tok")
    fake_dbtower.fail_next["analyze"] = [503]  # 마지막 배달도 일시 오류로 끝난다

    assert await worker.handle("1-0", {"jobId": job_id, "attempt": "1"}) == "dead"

    assert fake_dbtower.jobs[job_id]["status"] == "FAILED"
    assert "배달 2회" in fake_dbtower.jobs[job_id]["failureReason"]
    # 알림은 워커가 새로 만든 문안이 아니라 그래프의 FAILED -> notify 경로로 나간다
    messages = rec.bodies("chat.postMessage")
    assert len(messages) == 1
    assert "실패: 배달 2회" in messages[0]["text"]
    # 그리고 묻는다 — 알림이 나갔다고 메시지가 스트림에 남으면 다음 배달에서 또 돈다
    assert redis.acked == ["1-0"]
    assert [d["jobId"] for d in redis.dead] == [job_id]


@pytest.mark.asyncio
async def test_실패_알림이_실패해도_작업은_묻는다(fake_dbtower):
    job_id = fake_dbtower.add_job(status="ANALYZING", lease="tok")
    redis = FakeStreamRedis(times_delivered=2)
    worker, leases, rec = make_worker(fake_dbtower, redis, slack_ok=False)
    await leases.put(job_id, 1, "tok")
    fake_dbtower.fail_next["analyze"] = [503]

    assert await worker.handle("1-0", {"jobId": job_id, "attempt": "1"}) == "dead"

    assert fake_dbtower.jobs[job_id]["status"] == "FAILED"
    assert rec.bodies("chat.postMessage")  # 보내려 하기는 했다(채널이 거절했다)
    # 알림 실패가 재시도가 되면 배달 상한이 무의미해진다 — 묻는 것이 먼저다
    assert redis.acked == ["1-0"]
    assert [d["jobId"] for d in redis.dead] == [job_id]


@pytest.mark.asyncio
async def test_선점_전_실패에는_알릴_것이_없고_작업을_새로_선점하지도_않는다(fake_dbtower):
    job_id = fake_dbtower.add_job(status="RECEIVED")  # 리스가 없다 — 어떤 실행기도 선점하지 못한 작업
    redis = FakeStreamRedis(times_delivered=2)
    worker, _, rec = make_worker(fake_dbtower, redis)
    fake_dbtower.fail_next["claim"] = [503]

    assert await worker.handle("1-0", {"jobId": job_id, "attempt": "1"}) == "dead"

    # FAILED로 기록되지 않았다 — 리퍼가 "미선점"으로 드러낼 몫이고, 알릴 것도 없다.
    # 여기서 그래프를 불렀다면 start가 claim으로 가 작업을 선점해 버린다
    assert fake_dbtower.jobs[job_id]["status"] == "RECEIVED"
    assert not rec.requests
    assert redis.acked == ["1-0"]
    assert [d["jobId"] for d in redis.dead] == [job_id]
