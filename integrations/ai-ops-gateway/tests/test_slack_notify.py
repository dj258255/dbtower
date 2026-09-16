import hashlib
import hmac
import json

import pytest

from dbtower_aiops.notify import NotificationError, Notifier, n8n_payload, sign
from dbtower_aiops.slack import SlackClient, result_text, verify_signature

from .conftest import Recorder

SECRET = "test-signing-secret"


def slack_sign(body: bytes, ts: str) -> str:
    return "v0=" + hmac.new(SECRET.encode(), b"v0:" + ts.encode() + b":" + body, hashlib.sha256).hexdigest()


def test_서명_검증은_변조와_재생을_막는다():
    body, ts = b'{"a":1}', "1700000000"
    good = slack_sign(body, ts)
    assert verify_signature(SECRET, ts, body, good, now=1700000100)
    assert not verify_signature(SECRET, ts, b'{"a":2}', good, now=1700000100)
    assert not verify_signature(SECRET, ts, body, good, now=1700000000 + 301)
    assert not verify_signature("", ts, body, good, now=1700000100)


COMPLETED = {
    "jobId": "0f8a7c2e-aaaa", "type": "QUERY_DIAGNOSIS", "status": "COMPLETED", "replyChannel": "C1",
    "replyThread": "171.1", "requester": "slack:T1:U1", "scopeTeam": "team-a", "instanceId": 1, "prompt": "느려요",
    "result": {"facts": ["f1", "f2"], "ruleFindings": ["<@U999> 쿼리 q1 지연 304.2% 증가"],
               "aiOpinion": "커넥션 350개가 대기 중이다", "unverifiedClaims": ["사실 목록에 없는 수치: 350"],
               "uncertainties": [], "nextActions": ["인덱스 생성 검토"], "approvalRequired": True},
}


def test_검증_안_된_수치_경고가_소견보다_먼저_나오고_멘션은_무력화된다():
    text = result_text(COMPLETED, "https://dbtower.example")
    assert text.index("사실 목록과 대조되지 않은") < text.index("AI 1차 소견")
    assert "&lt;@U999&gt;" in text and "<@U999>" not in text
    assert "워크벤치 변경 요청" in text


def test_n8n에는_요청_문장과_소견_원문을_싣지_않는다():
    payload = n8n_payload(COMPLETED, "https://dbtower.example")
    dumped = json.dumps(payload, ensure_ascii=False)
    assert "느려요" not in dumped and "350개" not in dumped
    assert payload["approvalRequired"] is True and payload["unverifiedClaimCount"] == 1
    # 링크는 사람이 읽는 화면을 가리킨다
    assert payload["url"] == "https://dbtower.example/?aiop=0f8a7c2e-aaaa"


@pytest.mark.asyncio
async def test_알림은_채널별로_보내고_서명을_붙인다():
    rec = Recorder()
    slack = SlackClient("xoxb-test", "https://slack.test/api", transport=rec.transport())
    notifier = Notifier(slack, "https://dbtower.example", "https://n8n.test/webhook/dbtower", "n8n-secret",
                        transport=rec.transport())
    assert await notifier.send(COMPLETED) == ["slack", "n8n"]
    assert rec.bodies("chat.postMessage")[0]["thread_ts"] == "171.1"
    hook = [r for r in rec.requests if r.url.host == "n8n.test"][0]
    expected = sign("n8n-secret", hook.headers["X-DBTower-Timestamp"], hook.content)
    assert hook.headers["X-DBTower-Signature"] == expected


@pytest.mark.asyncio
async def test_Slack이_ok_false를_주면_실패로_올린다():
    rec = Recorder(slack_ok=False)
    notifier = Notifier(SlackClient("xoxb-test", "https://slack.test/api", transport=rec.transport()),
                        "https://dbtower.example")
    with pytest.raises(NotificationError):
        await notifier.send(COMPLETED)
