# AI 운영 작업 실행면

DBTower 바깥에서 도는 세 프로세스다. 설계와 경계는 [docs/AI-OPERATIONS-AUTOMATION.md](../../docs/AI-OPERATIONS-AUTOMATION.md),
실측은 [docs/VERIFICATION.md 169절](../../docs/VERIFICATION.md)에 있다.

| 프로세스 | 하는 일 |
|---|---|
| 게이트웨이 (`gateway`) | Slack 서명·중복·허용 채널 확인 → 문장을 작업 요청으로 옮겨 DBTower에 접수 → 스레드에 접수 답글 |
| 릴레이 (`relay`) | DBTower Outbox를 리스로 선점해 Redis Streams에 넣고 발행 완료를 알린다 |
| 실행기 (`worker`) | Redis Streams 소비 → LangGraph로 선점·사실·검색·분석·알림 → Slack 스레드와 n8n 웹훅 |

하지 않는 것: 대상 DB 접속, 권한 판정, 사실 만들기, 모델 호출. 전부 DBTower API를 거친다.
실행기가 하는 모델 호출도 DBTower의 `POST /{jobId}/analyze`이며, 백엔드(API 키·claude CLI)와 토큰 계수는 플랫폼 것이다.

## 준비

```bash
docker compose --profile aiops up -d aiops-redis aiops-vector    # 저장소 루트에서

cp .env.example .env         # 비밀값은 환경변수로만 — 파일을 커밋하지 않는다
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
```

`EMBEDDING_MODEL` 기본값(`intfloat/multilingual-e5-large`)은 약 2GB를 내려받아 메모리에 올린다.
가벼운 기계에서는 `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`로 바꿀 수 있지만,
한국어 운영 문장 기준 순위 품질이 떨어진다(169절 실측). 모델을 바꾸면 `ingest --rebuild`로 다시 적재해야 한다 —
차원이 다른 벡터를 섞어 검색하면 조용히 엉뚱한 문서가 나온다.

## 지식 적재

저장소의 운영 문서를 절(`##`) 단위로 넣는다. 런북을 새로 쓰지 않는다.
`경로:유형,유형`으로 그 문서가 어느 작업 유형의 참고 자료인지 사람이 정한다(비우면 모든 유형).

```bash
Q=QUERY_DIAGNOSIS,REGRESSION_EXPLANATION,INCIDENT_TRIAGE,DB_TEAM_INQUIRY
python -m dbtower_aiops ingest --rebuild \
  ../../docs/ai-analysis-rules.md:$Q \
  ../../docs/operations.md:$Q,ADVISOR_SUMMARY \
  ../../docs/least-privilege.md:INCIDENT_TRIAGE,DB_TEAM_INQUIRY,ADVISOR_SUMMARY
```

절 제목이 기종 이름(MySQL·PostgreSQL·SQL Server·Oracle·MongoDB)이면 그 기종 전용으로 표시되어,
다른 기종 작업의 후보에서 빠진다.

## 실행

```bash
uvicorn --factory dbtower_aiops.gateway:create_app --port 18090
python -m dbtower_aiops relay
python -m dbtower_aiops worker
```

Slack 앱에는 두 경로를 등록한다.

```text
Event Subscriptions  →  https://<게이트웨이>/slack/events      (app_mention)
Slash Command        →  https://<게이트웨이>/slack/commands
```

`SLACK_SIGNING_SECRET`이 없으면 두 경로는 404다(기능 게이트 — 엔드포인트 존재를 숨긴다).
채널과 사용자는 기본 거부다. `SLACK_CHANNEL_TEAMS`에 채널→팀을, `SLACK_USER_ALLOWLIST`에 사용자를 명시해야 받는다.

사용 예:

```text
@DBTower orders-db 최근 30분 느려진 쿼리 봐줘
/dbtower backup orders-db 복원 검증 됐나
```

문장에서 인스턴스를 못 찾으면 접수하지 않고 채널 범위 안의 후보 이름을 되묻는다.
작업 유형은 키워드 규칙으로 정한다(모델이 고르지 않는다 — 문장이 곧 범위가 되면 안 된다).

## 검증

```bash
python -m pytest            # Redis·pgvector가 없으면 해당 테스트는 건너뛴다
```

로컬 관통 검증에는 기록 서버를 쓴다. 실 Slack 워크스페이스·n8n 없이, 실행면이 보내려 한 요청을 파일로 남긴다.

```bash
python tools/record_server.py 18199 /tmp/recorded.jsonl
# .env에서 SLACK_API_BASE=http://127.0.0.1:18199/slack, N8N_WEBHOOK_URL=http://127.0.0.1:18199/n8n/...
```

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DBTOWER_URL` / `DBTOWER_API_TOKEN` | `http://localhost:8080` / - | 작업 API 주소와 서비스 토큰 |
| `DBTOWER_CONSOLE_URL` | `DBTOWER_URL` | 알림에 붙는 링크의 베이스(웹 콘솔 주소) |
| `REDIS_URL` | `redis://localhost:16379` | 큐와 리스 토큰 저장소 |
| `AI_OPS_STREAM` / `AI_OPS_GROUP` / `AI_OPS_CONSUMER` | `dbtower:ai-operations` / `dbtower-ai-workers` / 호스트명 | 스트림·소비자 그룹 |
| `AI_OPS_MAX_DELIVERIES` | `3` | 이 횟수를 넘기면 작업을 실패로 기록하고 사망 스트림으로 옮긴다 |
| `AI_OPS_CLAIM_IDLE_MS` | `60000` | 멈춘 메시지를 다시 가져오기까지의 유휴 시간. 분석 대기보다 길게 두거나 하트비트에 맡긴다 |
| `AI_OPS_ANALYZE_TIMEOUT_S` | `240` | 모델 호출을 포함한 분석 요청 상한 |
| `SLACK_SIGNING_SECRET` / `SLACK_BOT_TOKEN` | - | 서명 확인과 스레드 답글 |
| `SLACK_CHANNEL_TEAMS` / `SLACK_USER_ALLOWLIST` | - | `C123=team-a,C456=` 형식과 사용자 목록(둘 다 기본 거부) |
| `N8N_WEBHOOK_URL` / `N8N_WEBHOOK_SECRET` | - | 완료 이벤트 웹훅과 HMAC 서명 키 |
| `KNOWLEDGE_DSN` | - | pgvector 주소. 비우면 참고 자료 검색 없이 돈다 |
| `EMBEDDING_MODEL` | `intfloat/multilingual-e5-large` | 로컬 ONNX 임베딩(외부 API로 운영 문장을 보내지 않는다) |
| `KNOWLEDGE_INGEST_CASES` | `false` | 끝난 작업을 과거 사례로 적재. 켜면 AI 소견이 다음 참고 자료가 된다 |
