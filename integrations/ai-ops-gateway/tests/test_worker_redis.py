"""실제 Redis(docker compose --profile aiops)로 확인·재시도·격리 규칙을 본다. Redis가 없으면 건너뛴다."""
import uuid

import pytest
import pytest_asyncio
from redis.asyncio import Redis

from dbtower_aiops.dbtower import DBTowerClient, DBTowerError
from dbtower_aiops.relay import relay_once
from dbtower_aiops.worker import RedisLeaseStore, Worker, ensure_group

from .conftest import redis_url


@pytest_asyncio.fixture
async def redis():
    client = Redis.from_url(redis_url(), decode_responses=True)
    try:
        await client.ping()
    except Exception:  # noqa: BLE001
        pytest.skip("테스트용 Redis 없음(docker compose --profile aiops up -d aiops-redis)")
    yield client
    await client.aclose()


class ScriptedGraph:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0

    async def ainvoke(self, state):
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return {"outcome": outcome}


async def make_worker(redis, graph, fake, max_deliveries=2):
    stream = f"test:aiops:{uuid.uuid4().hex}"
    await ensure_group(redis, stream, "g")
    client = DBTowerClient("http://dbtower.test", "t", transport=fake.transport())
    worker = Worker(redis, client, graph, RedisLeaseStore(redis), stream=stream, dead_letter_stream=stream + ":dead",
                    group="g", consumer="c1", max_deliveries=max_deliveries, claim_idle_ms=1)
    return worker, stream


@pytest.mark.asyncio
async def test_일시_오류는_확인하지_않고_다시_가져와_성공하면_확인한다(redis, fake_dbtower):
    graph = ScriptedGraph(DBTowerError(503, "down"), "completed")
    worker, stream = await make_worker(redis, graph, fake_dbtower, max_deliveries=3)
    await redis.xadd(stream, {"jobId": "j1", "attempt": "1"})

    await worker.poll_once(block_ms=10)
    assert (await redis.xpending(stream, "g"))["pending"] == 1

    await worker.poll_once(block_ms=10)  # XAUTOCLAIM으로 다시 가져온다
    assert graph.calls == 2
    assert (await redis.xpending(stream, "g"))["pending"] == 0


@pytest.mark.asyncio
async def test_배달_상한을_넘기면_작업을_실패로_기록하고_사망_스트림으로_옮긴다(redis, fake_dbtower):
    job_id = fake_dbtower.add_job(status="ANALYZING", lease="tok")
    graph = ScriptedGraph(DBTowerError(503, "down"), DBTowerError(503, "down"))
    worker, stream = await make_worker(redis, graph, fake_dbtower, max_deliveries=2)
    await RedisLeaseStore(redis).put(job_id, 1, "tok")
    await redis.xadd(stream, {"jobId": job_id, "attempt": "1"})

    await worker.poll_once(block_ms=10)
    await worker.poll_once(block_ms=10)

    assert fake_dbtower.jobs[job_id]["status"] == "FAILED"
    assert "배달 2회" in fake_dbtower.jobs[job_id]["failureReason"]
    dead = await redis.xrange(stream + ":dead")
    assert dead and dead[0][1]["jobId"] == job_id
    assert (await redis.xpending(stream, "g"))["pending"] == 0


@pytest.mark.asyncio
async def test_409와_4xx는_재시도하지_않는다(redis, fake_dbtower):
    graph = ScriptedGraph(DBTowerError(409, "cancelled"), DBTowerError(403, "forbidden"))
    worker, stream = await make_worker(redis, graph, fake_dbtower, max_deliveries=5)
    await redis.xadd(stream, {"jobId": "j-409", "attempt": "1"})
    await redis.xadd(stream, {"jobId": "j-403", "attempt": "1"})

    await worker.poll_once(block_ms=10)

    assert graph.calls == 2
    assert (await redis.xpending(stream, "g"))["pending"] == 0
    assert [f["jobId"] for _, f in await redis.xrange(stream + ":dead")] == ["j-403"]


@pytest.mark.asyncio
async def test_릴레이는_식별자만_스트림에_넣는다(redis, fake_dbtower):
    stream = f"test:aiops:{uuid.uuid4().hex}"
    fake_dbtower.outbox = [{"eventId": "e1", "jobId": "j1", "eventType": "AiOperationSubmitted", "claimToken": "c",
                            "payload": '{"eventId":"e1","jobId":"j1","type":"SLO_RISK_REVIEW","attempt":2}'}]
    client = DBTowerClient("http://dbtower.test", "t", transport=fake_dbtower.transport())
    assert await relay_once(client, redis, stream) == 1
    [(_, fields)] = await redis.xrange(stream)
    assert fields == {"eventId": "e1", "jobId": "j1", "type": "SLO_RISK_REVIEW", "attempt": "2"}
