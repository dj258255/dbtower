"""사실 수집의 HTTP 제한 — 170절 1번.

모델 호출(`analyze_timeout_s`)만 240초로 빼두고 정작 대상 DB 5종을 직접 조회하는 사실 수집은 클라이언트 기본값
30초를 썼다. 그래서 주간 리포트 3건이 90.5~90.8초를 못 기다리고 전부 실패했다(사실은 플랫폼에 남았는데 소견도
알림도 없이). 여기서는 그 값이 설정에서 읽혀 `facts()`까지 가는지 본다.
"""
import httpx
import pytest

from dbtower_aiops.config import Settings
from dbtower_aiops.dbtower import DBTowerClient
from dbtower_aiops.graph import build_graph
from dbtower_aiops.notify import Notifier
from dbtower_aiops.slack import SlackClient

from .conftest import MemoryLeases, Recorder


def recording_transport(fake, seen):
    """가짜 DBTower에 요청을 그대로 넘기면서, 그 요청에 실린 읽기 제한만 받아 적는다.

    httpx는 요청별 timeout을 request.extensions["timeout"]에 남긴다 — 기본값을 쓰면 클라이언트 값(30초)이 보인다.
    """

    def handler(request):
        seen[request.url.path.rsplit("/", 1)[-1]] = request.extensions.get("timeout", {}).get("read")
        return fake.handle(request)

    return httpx.MockTransport(handler)


def test_수집_제한은_환경변수에서_읽고_모델_호출과_다른_기본값을_가진다(monkeypatch):
    monkeypatch.delenv("AI_OPS_COLLECT_TIMEOUT_S", raising=False)
    monkeypatch.delenv("AI_OPS_ANALYZE_TIMEOUT_S", raising=False)
    assert Settings().collect_timeout_s == 180.0
    assert Settings.from_env().collect_timeout_s == 180.0

    monkeypatch.setenv("AI_OPS_COLLECT_TIMEOUT_S", "185.5")
    assert Settings.from_env().collect_timeout_s == 185.5
    # 한 값으로 합치면 어느 쪽도 맞지 않는다 — 대상 조회(느림)와 모델 호출(더 느림)은 다른 단계다
    assert Settings.from_env().analyze_timeout_s == 240.0


@pytest.mark.asyncio
async def test_사실_수집은_설정된_제한으로_부르고_모델_호출은_제_한도를_쓴다(fake_dbtower):
    job_id = fake_dbtower.add_job()  # RECEIVED -> claim -> collect -> analyze -> notify
    seen: dict[str, float] = {}
    rec = Recorder()
    client = DBTowerClient("http://dbtower.test", "token", transport=recording_transport(fake_dbtower, seen))
    notifier = Notifier(SlackClient("xoxb", "https://slack.test/api", transport=rec.transport()),
                        "http://dbtower.test")
    graph = build_graph(client, notifier, MemoryLeases(), "w1", analyze_timeout_s=240.0, collect_timeout_s=185.5)

    await graph.ainvoke({"job_id": job_id, "attempt": 1})

    # 전에는 여기 30.0(클라이언트 기본값)이 실려 90초짜리 수집이 잘렸다
    assert seen["facts"] == 185.5
    assert seen["analyze"] == 240.0
