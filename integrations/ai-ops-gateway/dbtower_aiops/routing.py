"""Slack 문장을 작업 유형, 대상 인스턴스, 분석 구간으로 옮긴다.

모델에게 맡기지 않고 규칙으로 정한다. 요청 문장은 데이터라서, 무엇을 볼지(유형)와 어디를 볼지(대상)를
문장 해석 모델이 고르게 하면 "다른 팀 DB도 봐줘" 같은 문장이 곧 권한이 된다. 규칙이 못 정하면 되묻는다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))

# 앞에 있을수록 우선한다 — "백업 때문에 느려요"는 백업 문제로 본다(원인 쪽 단어를 먼저 둔다)
_TYPE_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("DB_TEAM_INQUIRY", ("문의", "db팀", "dba에게", "담당자")),
    ("PERIODIC_REPORT", ("리포트", "보고서", "주간", "월간", "요약해")),
    ("BACKUP_RISK_REVIEW", ("백업", "복원", "복구", "backup", "restore")),
    ("SLO_RISK_REVIEW", ("slo", "에러 버짓", "에러버짓", "가용성", "번인")),
    ("COST_REVIEW", ("비용", "낭비", "미사용 인덱스", "중복 인덱스", "finops")),
    ("ADVISOR_SUMMARY", ("advisor", "점검", "모범", "권고")),
    ("INCIDENT_TRIAGE", ("장애", "다운", "죽었", "안 붙", "접속이 안", "incident", "먹통")),
    ("REGRESSION_EXPLANATION", ("회귀", "평소보다", "갑자기", "어제보다", "배포 후", "regression")),
    ("QUERY_DIAGNOSIS", ("느려", "느린", "지연", "쿼리", "slow", "latency", "타임아웃", "락", "대기")),
]

_ALIASES = {
    "query": "QUERY_DIAGNOSIS", "쿼리": "QUERY_DIAGNOSIS",
    "regression": "REGRESSION_EXPLANATION", "회귀": "REGRESSION_EXPLANATION",
    "backup": "BACKUP_RISK_REVIEW", "백업": "BACKUP_RISK_REVIEW",
    "slo": "SLO_RISK_REVIEW",
    "advisor": "ADVISOR_SUMMARY", "점검": "ADVISOR_SUMMARY",
    "cost": "COST_REVIEW", "비용": "COST_REVIEW",
    "incident": "INCIDENT_TRIAGE", "장애": "INCIDENT_TRIAGE",
    "inquiry": "DB_TEAM_INQUIRY", "문의": "DB_TEAM_INQUIRY",
    "report": "PERIODIC_REPORT", "리포트": "PERIODIC_REPORT",
}

_WINDOW = re.compile(r"(\d{1,4})\s*(분|m\b|min|시간|h\b|hour|일|d\b|day)", re.IGNORECASE)
_MENTION = re.compile(r"<@[A-Z0-9]+>")
_MAX_WINDOW = 7 * 24 * 60


@dataclass(frozen=True)
class Routed:
    type: str
    instance_id: int | None
    instance_name: str | None
    window_minutes: int | None
    prompt: str


@dataclass(frozen=True)
class NeedsClarification:
    message: str


def clean(text: str) -> str:
    return _MENTION.sub("", text or "").strip()


def infer_type(text: str) -> str:
    lowered = text.lower()
    for type_name, words in _TYPE_KEYWORDS:
        if any(w in lowered for w in words):
            return type_name
    return "QUERY_DIAGNOSIS"


def infer_window(text: str, now: datetime | None = None) -> int | None:
    m = _WINDOW.search(text)
    if m:
        amount = int(m.group(1))
        unit = m.group(2).lower()
        minutes = amount if unit in ("분", "m", "min") else amount * 60 if unit in ("시간", "h", "hour") else amount * 1440
        return max(5, min(_MAX_WINDOW, minutes))
    now = now or datetime.now(KST)
    if "오늘" in text:
        midnight = now.astimezone(KST).replace(hour=0, minute=0, second=0, microsecond=0)
        return max(5, int((now - midnight).total_seconds() // 60))
    if "어제" in text:
        return 2 * 1440
    return None


def in_scope(instance: dict, team: str | None) -> bool:
    label = instance.get("teamLabel")
    return team is None or not label or label == team


def resolve_instance(text: str, instances: list[dict], team: str | None) -> dict | NeedsClarification:
    candidates = [i for i in instances if in_scope(i, team)]
    lowered = text.lower()
    # 가장 긴 이름부터 맞춘다 — "orders"와 "orders-db"가 둘 다 있으면 "orders-db 느려요"는 orders-db다
    matched = [i for i in sorted(candidates, key=lambda i: -len(i["name"])) if i["name"].lower() in lowered]
    if not matched:
        names = ", ".join(sorted(i["name"] for i in candidates)[:10]) or "(이 채널 범위에 등록된 인스턴스 없음)"
        return NeedsClarification(f"어느 인스턴스를 볼지 문장에서 찾지 못했습니다. 이름을 넣어 다시 요청해 주세요: {names}")
    longest = len(matched[0]["name"])
    top = [i for i in matched if len(i["name"]) == longest]
    if len(top) > 1:
        return NeedsClarification("같은 이름 길이로 겹치는 인스턴스가 여럿입니다: " + ", ".join(i["name"] for i in top))
    return top[0]


def route_mention(text: str, instances: list[dict], team: str | None, now: datetime | None = None) -> Routed | NeedsClarification:
    body = clean(text)
    if not body:
        return NeedsClarification("무엇을 볼지 적어 주세요. 예: orders-db 최근 30분 느려진 쿼리 봐줘")
    type_name = infer_type(body)
    if type_name == "PERIODIC_REPORT" and not any(i["name"].lower() in body.lower() for i in instances):
        return Routed(type_name, None, None, infer_window(body, now), body)
    target = resolve_instance(body, instances, team)
    if isinstance(target, NeedsClarification):
        return target
    return Routed(type_name, target["id"], target["name"], infer_window(body, now), body)


def route_command(text: str, instances: list[dict], team: str | None, now: datetime | None = None) -> Routed | NeedsClarification:
    """/dbtower <유형> <인스턴스> [구간] [질문] — 유형을 명시하면 키워드 추론보다 앞선다."""
    parts = clean(text).split(maxsplit=2)
    if len(parts) < 2 or parts[0].lower() not in _ALIASES:
        return NeedsClarification("사용법: /dbtower <query|regression|backup|slo|advisor|cost|incident|inquiry|report> "
                                  "<인스턴스> [30m|2h|1d] [질문]")
    type_name = _ALIASES[parts[0].lower()]
    rest = " ".join(parts[1:])
    if type_name == "PERIODIC_REPORT" and parts[1].lower() in ("all", "전체"):
        return Routed(type_name, None, None, infer_window(rest, now), rest)
    target = resolve_instance(parts[1], instances, team)
    if isinstance(target, NeedsClarification):
        return target
    return Routed(type_name, target["id"], target["name"], infer_window(rest, now), rest)
