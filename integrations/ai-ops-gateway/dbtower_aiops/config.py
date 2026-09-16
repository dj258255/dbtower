"""환경변수 설정. 비밀값(토큰, 서명 키, 웹훅 URL)은 환경변수로만 받는다."""
from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field


def _csv_map(raw: str) -> dict[str, str]:
    """"C123=team-a,C456=team-b" 형식. 값이 비면 전역 범위(None)로 본다."""
    out: dict[str, str] = {}
    for part in (raw or "").split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            if key.strip():
                out[key.strip()] = value.strip()
    return out


def _csv_set(raw: str) -> set[str]:
    return {p.strip() for p in (raw or "").split(",") if p.strip()}


@dataclass(frozen=True)
class Settings:
    dbtower_url: str = "http://localhost:8080"
    dbtower_token: str = ""
    dbtower_console_url: str = ""
    redis_url: str = "redis://localhost:16379"
    stream: str = "dbtower:ai-operations"
    dead_letter_stream: str = "dbtower:ai-operations:dead"
    group: str = "dbtower-ai-workers"
    consumer: str = field(default_factory=socket.gethostname)
    max_deliveries: int = 3
    claim_idle_ms: int = 60_000
    slack_signing_secret: str = ""
    slack_bot_token: str = ""
    slack_api_base: str = "https://slack.com/api"
    # 채널 -> 팀 범위. 목록에 없는 채널의 요청은 받지 않는다(기본 거부)
    slack_channel_teams: dict[str, str] = field(default_factory=dict)
    slack_user_allowlist: set[str] = field(default_factory=set)
    n8n_webhook_url: str = ""
    n8n_webhook_secret: str = ""
    knowledge_dsn: str = ""
    embedding_model: str = "intfloat/multilingual-e5-large"
    # 모델 호출(analyze)과 사실 수집(facts)의 HTTP 제한을 따로 둔다. 사실 수집은 대상 DB 5종을 직접 조회하는
    # 단계라(정기 리포트는 인스턴스 여러 대를 훑는다) 죽은 대상이 섞이면 30초를 넘긴다 — 170절에서 주간 리포트
    # 3건이 90.5~90.8초를 못 기다리고 전부 실패했다. 늘리면 대상이 죽었을 때 워커 슬롯을 그만큼 오래 쥔다(동시 처리 상한 4)
    analyze_timeout_s: float = 240.0
    collect_timeout_s: float = 180.0

    @staticmethod
    def from_env() -> "Settings":
        e = os.environ.get
        return Settings(
            dbtower_url=e("DBTOWER_URL", "http://localhost:8080").rstrip("/"),
            dbtower_token=e("DBTOWER_API_TOKEN", ""),
            dbtower_console_url=e("DBTOWER_CONSOLE_URL", e("DBTOWER_URL", "http://localhost:8080")).rstrip("/"),
            redis_url=e("REDIS_URL", "redis://localhost:16379"),
            stream=e("AI_OPS_STREAM", "dbtower:ai-operations"),
            dead_letter_stream=e("AI_OPS_DEAD_LETTER_STREAM", "dbtower:ai-operations:dead"),
            group=e("AI_OPS_GROUP", "dbtower-ai-workers"),
            consumer=e("AI_OPS_CONSUMER", "") or socket.gethostname(),
            max_deliveries=int(e("AI_OPS_MAX_DELIVERIES", "3")),
            claim_idle_ms=int(e("AI_OPS_CLAIM_IDLE_MS", "60000")),
            slack_signing_secret=e("SLACK_SIGNING_SECRET", ""),
            slack_bot_token=e("SLACK_BOT_TOKEN", ""),
            slack_api_base=e("SLACK_API_BASE", "https://slack.com/api").rstrip("/"),
            slack_channel_teams=_csv_map(e("SLACK_CHANNEL_TEAMS", "")),
            slack_user_allowlist=_csv_set(e("SLACK_USER_ALLOWLIST", "")),
            n8n_webhook_url=e("N8N_WEBHOOK_URL", ""),
            n8n_webhook_secret=e("N8N_WEBHOOK_SECRET", ""),
            knowledge_dsn=e("KNOWLEDGE_DSN", ""),
            embedding_model=e("EMBEDDING_MODEL", "intfloat/multilingual-e5-large"),
            analyze_timeout_s=float(e("AI_OPS_ANALYZE_TIMEOUT_S", "240")),
            collect_timeout_s=float(e("AI_OPS_COLLECT_TIMEOUT_S", "180")),
        )
