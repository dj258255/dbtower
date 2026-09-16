from datetime import datetime

from dbtower_aiops.routing import KST, NeedsClarification, Routed, infer_type, infer_window, route_command, route_mention

INSTANCES = [
    {"id": 1, "name": "orders-db", "teamLabel": "team-a"},
    {"id": 2, "name": "orders", "teamLabel": "team-a"},
    {"id": 3, "name": "billing-db", "teamLabel": "team-b"},
    {"id": 4, "name": "shared-pg", "teamLabel": None},
]


def test_원인_쪽_단어가_증상보다_우선한다():
    assert infer_type("백업 때문에 느려진 것 같아요") == "BACKUP_RISK_REVIEW"
    assert infer_type("orders-db 쿼리가 느려요") == "QUERY_DIAGNOSIS"
    assert infer_type("배포 후 갑자기 느려짐") == "REGRESSION_EXPLANATION"
    assert infer_type("그냥 봐줘") == "QUERY_DIAGNOSIS"


def test_가장_긴_인스턴스_이름을_고른다():
    routed = route_mention("<@U0BOT> orders-db 최근 30분 느려요", INSTANCES, "team-a")
    assert isinstance(routed, Routed)
    assert (routed.instance_id, routed.window_minutes, routed.prompt) == (1, 30, "orders-db 최근 30분 느려요")


def test_채널_팀_범위_밖_인스턴스는_후보가_아니다():
    routed = route_mention("billing-db 백업 확인", INSTANCES, "team-a")
    assert isinstance(routed, NeedsClarification)
    assert "billing-db" not in routed.message
    # 라벨 없는 인스턴스는 모든 팀이 본다(RegistryService와 같은 규칙)
    assert isinstance(route_mention("shared-pg 백업 확인", INSTANCES, "team-a"), Routed)


def test_구간_표현():
    assert infer_window("2시간 동안") == 120
    assert infer_window("최근 3일") == 4320
    assert infer_window("30일치") == 7 * 24 * 60  # 7일로 자른다
    now = datetime(2026, 9, 16, 9, 30, tzinfo=KST)
    assert infer_window("오늘 느려요", now) == 570
    assert infer_window("구간 말 없음") is None


def test_명령은_유형을_명시한다():
    routed = route_command("backup orders 2h 복원 검증 됐나", INSTANCES, "team-a")
    assert isinstance(routed, Routed)
    assert (routed.type, routed.instance_id, routed.window_minutes) == ("BACKUP_RISK_REVIEW", 2, 120)
    assert isinstance(route_command("모르는유형 orders", INSTANCES, "team-a"), NeedsClarification)
    report = route_command("report all 1d", INSTANCES, "team-a")
    assert isinstance(report, Routed) and report.instance_id is None
