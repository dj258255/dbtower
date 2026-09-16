"""실행 진입점.

    python -m dbtower_aiops relay                     Outbox -> Redis Streams
    python -m dbtower_aiops worker                    Redis Streams -> LangGraph 실행기
    python -m dbtower_aiops ingest [--rebuild] 파일[:유형,...]... 운영 문서를 절 단위로 지식 저장소에 적재
    uvicorn --factory dbtower_aiops.gateway:create_app Slack 입구
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
from pathlib import Path


from . import relay
from .config import Settings
from .dbtower import DBTowerClient
from .graph import build_graph
from .knowledge import FastEmbedder, KnowledgeStore, markdown_sections
from .notify import Notifier
from .slack import SlackClient
from .worker import RedisLeaseStore, Worker, connect


async def run_relay(settings: Settings) -> None:
    client = DBTowerClient(settings.dbtower_url, settings.dbtower_token)
    redis = connect(settings.redis_url)
    try:
        await relay.run_forever(client, redis, settings.stream)
    finally:
        await client.aclose()
        await redis.aclose()


async def run_worker(settings: Settings) -> None:
    client = DBTowerClient(settings.dbtower_url, settings.dbtower_token)
    redis = connect(settings.redis_url)
    slack = SlackClient(settings.slack_bot_token, settings.slack_api_base)
    notifier = Notifier(slack, settings.dbtower_console_url, settings.n8n_webhook_url, settings.n8n_webhook_secret)
    store = KnowledgeStore(settings.knowledge_dsn, FastEmbedder(settings.embedding_model)) \
        if settings.knowledge_dsn else None
    leases = RedisLeaseStore(redis)
    graph = build_graph(client, notifier, leases, settings.consumer, store, settings.analyze_timeout_s,
                        ingest_cases=os.environ.get("KNOWLEDGE_INGEST_CASES", "false").lower() == "true")
    worker = Worker(redis, client, graph, leases, stream=settings.stream, dead_letter_stream=settings.dead_letter_stream,
                    group=settings.group, consumer=settings.consumer, max_deliveries=settings.max_deliveries,
                    claim_idle_ms=settings.claim_idle_ms)
    try:
        await worker.run_forever()
    finally:
        await client.aclose()
        await notifier.aclose()
        await slack.aclose()
        await redis.aclose()
        if store:
            store.close()


def ingest(settings: Settings, paths: list[str], rebuild: bool) -> None:
    if not settings.knowledge_dsn:
        raise SystemExit("KNOWLEDGE_DSN이 없습니다")
    store = KnowledgeStore(settings.knowledge_dsn, FastEmbedder(settings.embedding_model), rebuild=rebuild)
    try:
        documents = []
        for spec in paths:
            # 경로:유형,유형 — 문서 용도는 사람이 정한다. 유형을 비우면 모든 작업 유형의 후보가 된다
            path, _, types = spec.partition(":")
            job_types = tuple(t.strip() for t in types.split(",") if t.strip()) or None
            documents.extend(markdown_sections(Path(path), job_types))
        written = store.upsert(documents)
        print(f"문서 {len(documents)}절 중 {written}절을 새로 적재했습니다. 저장소 전체 {store.count()}절")
    finally:
        store.close()


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s %(message)s")
    # 릴레이는 매초 Outbox를 본다 — httpx 요청 한 줄씩 남기면 로그가 폴링으로 덮인다
    logging.getLogger("httpx").setLevel(logging.WARNING)
    parser = argparse.ArgumentParser(prog="dbtower_aiops")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("relay")
    sub.add_parser("worker")
    ingest_parser = sub.add_parser("ingest")
    ingest_parser.add_argument("--rebuild", action="store_true")
    ingest_parser.add_argument("paths", nargs="+")
    args = parser.parse_args()
    settings = Settings.from_env()
    if args.command == "relay":
        asyncio.run(run_relay(settings))
    elif args.command == "worker":
        asyncio.run(run_worker(settings))
    else:
        ingest(settings, args.paths, args.rebuild)


if __name__ == "__main__":
    main()
