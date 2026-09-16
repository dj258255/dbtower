"""Slack 입구 (FastAPI).

DBTower를 인터넷에 직접 열지 않으려고 둔 얇은 입구다. 하는 일은 서명 확인, 중복 이벤트 차단, 채널·사용자 허용 확인,
문장을 작업 요청으로 옮기기, DBTower에 접수, 스레드에 접수 답글까지. 분석·모델 호출·대상 DB 접속은 하지 않는다.

Slack은 3초 안에 응답이 없으면 같은 이벤트를 다시 보낸다. 그래서 확인 뒤 바로 200을 주고 접수는 백그라운드에서 한다.
재전송은 event_id로 Redis에서 막고, 그래도 겹치면 DBTower의 requestId 멱등이 한 번 더 막는다.
"""
from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager
from typing import Any
from urllib.parse import parse_qs

from fastapi import BackgroundTasks, FastAPI, Request, Response
from redis.asyncio import Redis

from .config import Settings
from .dbtower import DBTowerClient, DBTowerError
from .routing import NeedsClarification, Routed, route_command, route_mention
from .slack import SlackClient, accepted_text, verify_signature
from .worker import connect

log = logging.getLogger("dbtower_aiops.gateway")

DEDUP_TTL_S = 24 * 3600
INSTANCE_CACHE_S = 60


class Gateway:
    def __init__(self, settings: Settings, redis: Redis, dbtower: DBTowerClient, slack: SlackClient):
        self.settings = settings
        self.redis = redis
        self.dbtower = dbtower
        self.slack = slack
        self._instances: list[dict[str, Any]] = []
        self._instances_at = 0.0

    async def instances(self) -> list[dict[str, Any]]:
        if time.monotonic() - self._instances_at > INSTANCE_CACHE_S:
            self._instances = await self.dbtower.instances()
            self._instances_at = time.monotonic()
        return self._instances

    def allowed(self, channel: str, user: str) -> bool:
        # 기본 거부 — 채널이 팀 범위 표에 없거나 사용자가 허용 목록에 없으면 받지 않는다(Java Slack 인바운드와 같은 규칙)
        return channel in self.settings.slack_channel_teams and user in self.settings.slack_user_allowlist

    def team_of(self, channel: str) -> str | None:
        return self.settings.slack_channel_teams.get(channel) or None

    async def first_seen(self, key: str) -> bool:
        return bool(await self.redis.set(f"dbtower:slack:seen:{key}", "1", nx=True, ex=DEDUP_TTL_S))

    async def accept(self, *, request_id: str, routed: Routed | NeedsClarification, slack_team: str, user: str,
                     channel: str, thread_ts: str | None) -> dict[str, Any] | None:
        if isinstance(routed, NeedsClarification):
            await self.reply(channel, thread_ts, routed.message)
            return None
        request = {
            "requestId": request_id, "type": routed.type, "instanceId": routed.instance_id,
            "windowMinutes": routed.window_minutes, "prompt": routed.prompt, "trigger": "SLACK",
            "requester": f"slack:{slack_team}:{user}", "team": self.team_of(channel),
            "replyChannel": channel, "replyThread": thread_ts,
        }
        try:
            job = await self.dbtower.submit(request)
        except DBTowerError as exc:
            # 진행 중 상한(409)·범위 밖(404)은 사용자가 이해할 수 있게 그대로 알린다
            message = {404: "이 채널 범위에서 볼 수 없는 인스턴스입니다.", 409: exc.message}.get(exc.status,
                                                                                    "접수하지 못했습니다. 잠시 뒤 다시 요청해 주세요.")
            await self.reply(channel, thread_ts, message)
            log.warning("접수 실패 request=%s status=%s", request_id, exc.status)
            return None
        await self.reply(channel, thread_ts, accepted_text(job, routed.instance_name))
        return job

    async def reply(self, channel: str, thread_ts: str | None, text: str) -> None:
        try:
            await self.slack.post_thread(channel, thread_ts, text)
        except Exception as exc:  # noqa: BLE001 - 답글 실패가 접수를 되돌리지 않는다
            log.warning("Slack 답글 실패 channel=%s: %s", channel, exc)


def create_app(settings: Settings | None = None, *, redis: Redis | None = None, dbtower: DBTowerClient | None = None,
               slack: SlackClient | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.gateway = Gateway(
            settings,
            redis or connect(settings.redis_url),
            dbtower or DBTowerClient(settings.dbtower_url, settings.dbtower_token),
            slack or SlackClient(settings.slack_bot_token, settings.slack_api_base))
        yield
        await app.state.gateway.dbtower.aclose()
        await app.state.gateway.slack.aclose()

    app = FastAPI(title="DBTower AI Operations Gateway", lifespan=lifespan)

    async def verified_body(request: Request) -> bytes | None:
        body = await request.body()
        ok = verify_signature(settings.slack_signing_secret, request.headers.get("X-Slack-Request-Timestamp"), body,
                              request.headers.get("X-Slack-Signature"))
        return body if ok else None

    @app.get("/health")
    async def health(request: Request) -> dict[str, str]:
        await request.app.state.gateway.redis.ping()
        return {"status": "UP"}

    @app.post("/slack/events")
    async def events(request: Request, background: BackgroundTasks) -> Response:
        if not settings.slack_signing_secret:
            return Response(status_code=404)  # 기능 게이트 — 서명 키가 없으면 입구 자체를 숨긴다
        body = await verified_body(request)
        if body is None:
            return Response(status_code=401)
        payload = await _json(request)
        if payload.get("type") == "url_verification":
            return Response(content=payload.get("challenge", ""), media_type="text/plain")
        gateway: Gateway = request.app.state.gateway
        event = payload.get("event") or {}
        event_id = payload.get("event_id")
        if event.get("type") != "app_mention" or not event_id or event.get("bot_id"):
            return Response(status_code=200)
        channel, user = event.get("channel", ""), event.get("user", "")
        if not gateway.allowed(channel, user):
            log.info("허용되지 않은 채널·사용자 channel=%s user=%s", channel, user)
            return Response(status_code=200)
        if not await gateway.first_seen(f"event:{event_id}"):
            return Response(status_code=200)
        thread_ts = event.get("thread_ts") or event.get("ts")

        async def work() -> None:
            try:
                instances = await gateway.instances()
            except DBTowerError as exc:
                log.warning("인스턴스 목록 조회 실패: %s", exc)
                await gateway.reply(channel, thread_ts, "DBTower에 연결하지 못해 접수하지 못했습니다. 잠시 뒤 다시 요청해 주세요.")
                return
            routed = route_mention(event.get("text", ""), instances, gateway.team_of(channel))
            await gateway.accept(request_id=f"slack:{event_id}", routed=routed, slack_team=payload.get("team_id", ""),
                                 user=user, channel=channel, thread_ts=thread_ts)

        background.add_task(work)
        return Response(status_code=200)

    @app.post("/slack/commands")
    async def commands(request: Request, background: BackgroundTasks) -> Response:
        if not settings.slack_signing_secret:
            return Response(status_code=404)
        body = await verified_body(request)
        if body is None:
            return Response(status_code=401)
        form = {k: v[0] for k, v in parse_qs(body.decode()).items()}
        gateway: Gateway = request.app.state.gateway
        channel, user = form.get("channel_id", ""), form.get("user_id", "")
        if not gateway.allowed(channel, user):
            return Response(content="이 채널 또는 사용자는 DBTower AI 작업을 요청할 수 없습니다.", media_type="text/plain")
        trigger_id = form.get("trigger_id") or f"{channel}:{user}:{time.time_ns()}"
        if not await gateway.first_seen(f"command:{trigger_id}"):
            return Response(status_code=200)

        async def work() -> None:
            try:
                instances = await gateway.instances()
            except DBTowerError as exc:
                log.warning("인스턴스 목록 조회 실패: %s", exc)
                await gateway.reply(channel, None, "DBTower에 연결하지 못해 접수하지 못했습니다. 잠시 뒤 다시 요청해 주세요.")
                return
            routed = route_command(form.get("text", ""), instances, gateway.team_of(channel))
            await gateway.accept(request_id=f"slack-command:{trigger_id}", routed=routed,
                                 slack_team=form.get("team_id", ""), user=user, channel=channel, thread_ts=None)

        background.add_task(work)
        return Response(content="요청을 확인하고 있습니다.", media_type="text/plain")

    return app


async def _json(request: Request) -> dict[str, Any]:
    try:
        return await request.json()
    except ValueError:
        return {}

