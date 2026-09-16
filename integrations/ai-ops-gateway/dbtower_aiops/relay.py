"""Outbox -> Redis Streams 릴레이.

DBTower Outbox에서 이벤트를 리스로 선점해 스트림에 넣고 발행 완료를 알린다. XADD 뒤 완료를 알리기 전에 죽으면
리스가 만료된 뒤 같은 이벤트가 다시 발행된다 — 유실보다 중복을 택했고, 중복은 작업 선점이 흡수한다.
"""
from __future__ import annotations

import asyncio
import json
import logging

from redis.asyncio import Redis
from redis.exceptions import RedisError

from .dbtower import DBTowerClient, DBTowerError

log = logging.getLogger("dbtower_aiops.relay")

# 소비되지 않은 메시지까지 잘라내지 않도록 넉넉히 둔다(근사 절단). 사망 스트림은 자르지 않는다
STREAM_MAXLEN = 100_000


async def relay_once(client: DBTowerClient, redis: Redis, stream: str, limit: int = 50) -> int:
    published = 0
    for event in await client.claim_outbox(limit):
        payload = json.loads(event["payload"])
        # 큐에는 식별자만 흐른다 — 요청 문장은 권한 검사를 거친 API로만 읽는다
        fields = {"eventId": event["eventId"], "jobId": payload["jobId"], "type": payload["type"],
                  "attempt": str(payload["attempt"])}
        await redis.xadd(stream, fields, maxlen=STREAM_MAXLEN, approximate=True)
        if await client.mark_published(event["eventId"], event["claimToken"]):
            published += 1
        else:
            log.warning("발행 완료를 기록하지 못함(리스 만료로 다른 릴레이가 가져감) event=%s", event["eventId"])
    return published


async def run_forever(client: DBTowerClient, redis: Redis, stream: str, interval_s: float = 1.0) -> None:
    while True:
        try:
            if await relay_once(client, redis, stream):
                continue  # 밀린 이벤트가 있으면 쉬지 않고 이어서 비운다
        except DBTowerError as exc:
            log.warning("Outbox 선점 실패, 잠시 뒤 다시 시도: %s", exc)
        except (RedisError, ConnectionError, OSError) as exc:
            log.warning("Redis 오류, 잠시 뒤 다시 시도: %s", exc)
        await asyncio.sleep(interval_s)
