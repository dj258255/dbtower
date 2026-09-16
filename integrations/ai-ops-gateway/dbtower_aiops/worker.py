"""Redis Streams 소비자 — 메시지 하나를 그래프 한 번으로 실행하고, 결과에 따라 확인·재시도·격리한다.

확인(XACK) 규칙:
  - 끝남·다른 실행기의 작업·옛 시도 -> 확인
  - 일시 오류(DBTower 5xx·연결 실패·알림 실패) -> 확인하지 않는다. 멈춘 메시지는 XAUTOCLAIM으로 다시 가져와 이어간다
  - 배달 횟수가 상한을 넘거나 재시도해도 같은 결과인 오류(4xx) -> 작업을 실패로 기록하고 사망 스트림으로 옮긴 뒤 확인.
    배달 상한으로 닫을 때는 묻기 전에 실패 알림을 한 번 보낸다(묻으면 재배달이 없어 알림 경로가 사라진다 — 170절 2번)

재시도가 처음부터가 아니라 이어서 되는 이유는 리스 토큰을 Redis에 남기기 때문이다(graph.start 참고).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from redis.asyncio import Redis
from redis.exceptions import RedisError, ResponseError

from .dbtower import DBTowerClient, DBTowerError
from .notify import NotificationError

log = logging.getLogger("dbtower_aiops.worker")

LEASE_TTL_S = 24 * 3600
BLOCK_MS = 5000
# 블로킹 읽기보다 소켓 읽기 제한이 길어야 한다 — 같으면 요청이 없는 매 5초마다 읽기 시간 초과가 난다
SOCKET_TIMEOUT_S = BLOCK_MS / 1000 + 10


def connect(url: str) -> Redis:
    return Redis.from_url(url, decode_responses=True, socket_timeout=SOCKET_TIMEOUT_S,
                          socket_connect_timeout=5, health_check_interval=30)


class RedisLeaseStore:
    def __init__(self, redis: Redis):
        self._redis = redis

    @staticmethod
    def _key(job_id: str, attempt: int) -> str:
        return f"dbtower:ai-operations:lease:{job_id}:{attempt}"

    async def get(self, job_id: str, attempt: int) -> str | None:
        return await self._redis.get(self._key(job_id, attempt))

    async def put(self, job_id: str, attempt: int, token: str) -> None:
        await self._redis.set(self._key(job_id, attempt), token, ex=LEASE_TTL_S)


async def ensure_group(redis: Redis, stream: str, group: str) -> None:
    try:
        await redis.xgroup_create(stream, group, id="0", mkstream=True)
    except ResponseError as exc:
        if "BUSYGROUP" not in str(exc):
            raise


class Worker:
    def __init__(self, redis: Redis, client: DBTowerClient, graph: Any, leases: RedisLeaseStore, *,
                 stream: str, dead_letter_stream: str, group: str, consumer: str,
                 max_deliveries: int = 3, claim_idle_ms: int = 60_000, concurrency: int = 4):
        self._redis = redis
        self._client = client
        self._graph = graph
        self._leases = leases
        self._stream = stream
        self._dead = dead_letter_stream
        self._group = group
        self._consumer = consumer
        self._max_deliveries = max_deliveries
        self._claim_idle_ms = claim_idle_ms
        self._concurrency = concurrency
        self._slots = asyncio.Semaphore(concurrency)

    async def handle(self, message_id: str, fields: dict[str, str]) -> str:
        """메시지 하나를 처리하고 무엇을 했는지 돌려준다(acked / retry / dead)."""
        try:
            job_id = fields["jobId"]
            attempt = int(fields.get("attempt", "1"))
        except (KeyError, ValueError):
            await self._bury(message_id, fields, "메시지 형식 오류")
            return "dead"
        try:
            state = await self._graph.ainvoke({"job_id": job_id, "attempt": attempt})
            await self._redis.xack(self._stream, self._group, message_id)
            log.info("작업 처리 job=%s outcome=%s", job_id, state.get("outcome"))
            return "acked"
        except (NotificationError, DBTowerError, OSError) as exc:
            if isinstance(exc, DBTowerError) and not exc.transient:
                # 409는 작업 상태가 이미 바뀐 것(취소·다른 실행기)이라 확인만 한다
                if exc.conflict:
                    await self._redis.xack(self._stream, self._group, message_id)
                    log.info("작업 상태가 먼저 바뀌어 넘어감 job=%s: %s", job_id, exc)
                    return "acked"
                # 그 밖의 4xx는 다시 보내도 같다(권한·검증 실패)
                await self._fail_job(job_id, attempt, f"실행기 요청이 거부됨: {exc}")
                await self._bury(message_id, fields, str(exc))
                return "dead"
            deliveries = await self._deliveries(message_id)
            if deliveries < self._max_deliveries:
                log.warning("일시 오류로 재시도 대기 job=%s delivery=%d: %s", job_id, deliveries, exc)
                return "retry"
            failed = await self._fail_job(job_id, attempt, f"배달 {deliveries}회 뒤에도 실패: {exc}")
            # 작업이 FAILED로 기록됐을 때만 알린다. 리스가 없어 기록이 안 된 경우(선점 전 실패)에 그래프를 부르면
            # start가 claim으로 가 작업을 새로 선점해 버린다 — 알릴 것도 없다
            if failed:
                # 묻기 전에 알린다. _bury는 XACK이라 재배달이 없고, 그래프의 알림은 재배달(start의 FAILED -> notify)로만
                # 나가므로 여기서 부르지 않으면 리퍼가 닫은 작업만 온콜에 닿는다(170절 2번)
                await self._notify_failed(job_id, attempt)
            await self._bury(message_id, fields, str(exc))
            return "dead"

    async def _deliveries(self, message_id: str) -> int:
        pending = await self._redis.xpending_range(self._stream, self._group, min=message_id, max=message_id, count=1)
        return int(pending[0]["times_delivered"]) if pending else 1

    async def _fail_job(self, job_id: str, attempt: int, reason: str) -> bool:
        """작업을 FAILED로 기록한다. 기록했으면 True — 실패 알림을 부를지는 여기에 달렸다."""
        token = await self._leases.get(job_id, attempt)
        if not token:
            return False  # 선점 전에 실패했다 — 작업은 RECEIVED로 남고 리퍼가 미선점으로 드러낸다
        try:
            await self._client.fail(job_id, token, reason)
            return True
        except DBTowerError as exc:
            # 이미 끝난 작업(알림만 실패)이거나 DBTower가 아직 내려가 있다 — 사망 스트림 기록이 흔적으로 남는다
            log.warning("작업 실패 기록 못함 job=%s: %s", job_id, exc)
            return False

    async def _notify_failed(self, job_id: str, attempt: int) -> None:
        """FAILED로 기록된 작업의 실패 알림을 한 번 보낸다 — 그래프의 start가 FAILED -> notify로 보낸다.

        알림 문안·수신자 결정은 실행면과 플랫폼에 한 곳씩만 있어야 하므로(notifier·채널 표) 여기서 새로 만들지 않는다.
        알림이 실패해도 예외를 올리지 않는다 — 올리면 handle()을 타고 배달 상한을 넘겨 재시도가 무한해진다.
        """
        try:
            await self._graph.ainvoke({"job_id": job_id, "attempt": attempt})
        except Exception as exc:  # noqa: BLE001 - 알림 실패가 무한 재시도가 되면 안 된다
            log.warning("실패 알림을 보내지 못함 job=%s: %s", job_id, exc)

    async def _bury(self, message_id: str, fields: dict[str, str], reason: str) -> None:
        await self._redis.xadd(self._dead, {**fields, "sourceId": message_id, "reason": reason[:500]})
        await self._redis.xack(self._stream, self._group, message_id)

    async def _heartbeat(self, message_id: str) -> None:
        # 처리 중인 메시지의 유휴 시간을 계속 0으로 되돌린다. 모델 대기(수십 초~수 분)가 재회수 기준보다 길면
        # 다른 실행기가 아직 살아 있는 메시지를 가져가 모델을 한 번 더 부른다
        interval = max(1.0, self._claim_idle_ms / 3000)
        while True:
            await asyncio.sleep(interval)
            await self._redis.xclaim(self._stream, self._group, self._consumer, min_idle_time=0,
                                     message_ids=[message_id], justid=True)

    async def _run_one(self, message_id: str, fields: dict[str, str]) -> None:
        async with self._slots:
            beat = asyncio.create_task(self._heartbeat(message_id))
            try:
                await self.handle(message_id, fields)
            except Exception:  # noqa: BLE001 - 한 메시지의 예상 밖 오류가 소비 루프를 죽이지 않게
                log.exception("메시지 처리 중 예상 밖 오류 id=%s", message_id)
            finally:
                beat.cancel()

    async def _fetch(self, count: int, block_ms: int) -> list[tuple[str, dict[str, str]]]:
        """가져올 메시지 — 멈춘 것(다른 실행기가 죽었거나 일시 오류로 확인 못한 것)을 먼저, 그다음 새 것."""
        _, reclaimed, _ = await self._redis.xautoclaim(self._stream, self._group, self._consumer,
                                                       min_idle_time=self._claim_idle_ms, start_id="0-0", count=count)
        messages = [(message_id, fields) for message_id, fields in reclaimed if fields]
        room = count - len(messages)
        if room > 0:
            batches = await self._redis.xreadgroup(self._group, self._consumer, {self._stream: ">"}, count=room,
                                                   block=block_ms)
            for _, batch in batches or []:
                messages.extend(batch)
        return messages

    async def poll_once(self, block_ms: int = BLOCK_MS) -> int:
        """한 번 읽어 그 메시지를 끝까지 처리하고 돌아온다 — 테스트와 단발 실행용. 운영 루프는 run_forever다."""
        tasks = [asyncio.create_task(self._run_one(message_id, fields))
                 for message_id, fields in await self._fetch(10, block_ms)]
        if tasks:
            await asyncio.gather(*tasks)
        return len(tasks)

    async def run_forever(self, block_ms: int = BLOCK_MS) -> None:
        """자리가 나는 대로 읽는다.

        예전에는 poll_once를 돌려 한 배치의 메시지를 전부 기다린 뒤에야 다음을 읽었다. 동시 처리 상한(4)은 세마포어로
        따로 있었지만 읽기가 배치 단위라, 느린 한 건이 끝날 때까지 새 메시지를 아예 읽지 않았다 — 170절에서 워커가 한 건만
        돌리던 중 도착한 작업이 10.76초 늦게 선점됐다. 사실 수집 제한을 180초로 늘린 뒤로는 그 대기가 최대 180초가 된다.

        비어 있는 자리만큼만 읽는다. 자리보다 많이 읽으면 남는 메시지가 세마포어를 기다리는 동안 심장박동이 없어
        유휴 시간이 자라고, 다른 실행기가 아직 살아 있는 메시지를 재회수해 모델을 한 번 더 부른다.
        """
        await ensure_group(self._redis, self._stream, self._group)
        in_flight: set[asyncio.Task[None]] = set()
        while True:
            try:
                room = self._concurrency - len(in_flight)
                if room <= 0:
                    await asyncio.wait(in_flight, return_when=asyncio.FIRST_COMPLETED)
                    continue
                for message_id, fields in await self._fetch(room, block_ms):
                    task = asyncio.create_task(self._run_one(message_id, fields))
                    in_flight.add(task)
                    task.add_done_callback(in_flight.discard)
            except (RedisError, ConnectionError, OSError) as exc:
                # redis-py의 TimeoutError·ConnectionError는 내장 예외 계열이 아니다 — 내장 것만 잡자 첫 유휴 5초에 프로세스가 죽었다
                log.warning("Redis 오류, 잠시 뒤 다시 시도: %s", exc)
                await asyncio.sleep(2)
