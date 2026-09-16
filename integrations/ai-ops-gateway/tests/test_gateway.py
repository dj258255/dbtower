import hashlib
import hmac
import json
import time
from urllib.parse import urlencode

from fastapi.testclient import TestClient

from dbtower_aiops.config import Settings
from dbtower_aiops.dbtower import DBTowerClient
from dbtower_aiops.gateway import create_app
from dbtower_aiops.slack import SlackClient

from .conftest import Recorder

SECRET = "gw-secret"


class MemoryRedis:
    def __init__(self):
        self.keys = {}

    async def set(self, key, value, nx=False, ex=None):
        if nx and key in self.keys:
            return None
        self.keys[key] = value
        return True

    async def ping(self):
        return True


def signed(body: bytes) -> dict:
    ts = str(int(time.time()))
    sig = "v0=" + hmac.new(SECRET.encode(), b"v0:" + ts.encode() + b":" + body, hashlib.sha256).hexdigest()
    return {"X-Slack-Request-Timestamp": ts, "X-Slack-Signature": sig, "Content-Type": "application/json"}


def client_for(fake):
    rec = Recorder()
    settings = Settings(slack_signing_secret=SECRET, slack_channel_teams={"C1": "team-a"},
                        slack_user_allowlist={"U1"})
    app = create_app(settings, redis=MemoryRedis(),
                     dbtower=DBTowerClient("http://dbtower.test", "t", transport=fake.transport()),
                     slack=SlackClient("xoxb", "https://slack.test/api", transport=rec.transport()))
    return TestClient(app), rec


def mention(event_id: str, text: str, channel="C1", user="U1") -> bytes:
    return json.dumps({"type": "event_callback", "event_id": event_id, "team_id": "T1",
                       "event": {"type": "app_mention", "text": f"<@UBOT> {text}", "channel": channel, "user": user,
                                 "ts": "171.1"}}).encode()


def test_서명이_틀리면_401이고_URL_확인은_challenge를_돌려준다(fake_dbtower):
    client, _ = client_for(fake_dbtower)
    with client:
        body = mention("Ev0", "orders-db 느려요")
        assert client.post("/slack/events", content=body, headers={**signed(body), "X-Slack-Signature": "v0=bad"}).status_code == 401
        challenge = json.dumps({"type": "url_verification", "challenge": "abc"}).encode()
        assert client.post("/slack/events", content=challenge, headers=signed(challenge)).text == "abc"


def test_재전송된_이벤트는_한_번만_접수된다(fake_dbtower):
    client, rec = client_for(fake_dbtower)
    with client:
        body = mention("Ev1", "orders-db 최근 30분 느려요")
        for _ in range(3):  # Slack의 3초 재전송
            assert client.post("/slack/events", content=body, headers=signed(body)).status_code == 200
    assert len(fake_dbtower.submitted) == 1
    request = fake_dbtower.submitted[0]
    assert request["requestId"] == "slack:Ev1" and request["instanceId"] == 1 and request["windowMinutes"] == 30
    assert request["requester"] == "slack:T1:U1" and request["team"] == "team-a"
    assert rec.bodies("chat.postMessage")[0]["text"].startswith("접수했습니다")


def test_허용되지_않은_채널과_사용자는_조용히_버린다(fake_dbtower):
    client, rec = client_for(fake_dbtower)
    with client:
        for body in (mention("Ev2", "orders-db 느려요", channel="C9"), mention("Ev3", "orders-db 느려요", user="U9")):
            assert client.post("/slack/events", content=body, headers=signed(body)).status_code == 200
    assert not fake_dbtower.submitted and not rec.requests


def test_대상을_못_찾으면_접수하지_않고_되묻는다(fake_dbtower):
    client, rec = client_for(fake_dbtower)
    with client:
        body = mention("Ev4", "billing-db 백업 봐줘")  # team-b 인스턴스 — 이 채널(team-a) 범위 밖
        client.post("/slack/events", content=body, headers=signed(body))
    assert not fake_dbtower.submitted
    assert "찾지 못했습니다" in rec.bodies("chat.postMessage")[0]["text"]


def test_슬래시_명령(fake_dbtower):
    client, _ = client_for(fake_dbtower)
    with client:
        body = urlencode({"channel_id": "C1", "user_id": "U1", "team_id": "T1", "trigger_id": "tr1",
                          "text": "backup orders-db 2h"}).encode()
        headers = {**signed(body), "Content-Type": "application/x-www-form-urlencoded"}
        assert client.post("/slack/commands", content=body, headers=headers).status_code == 200
    assert fake_dbtower.submitted[0]["type"] == "BACKUP_RISK_REVIEW"
    assert fake_dbtower.submitted[0]["replyThread"] is None
