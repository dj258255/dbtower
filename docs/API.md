# API 빠른 참조

웹 콘솔, MCP, 자동화 채널은 같은 서비스 코어를 사용합니다. 이 문서는 자주 쓰는 REST
진입점을 빠르게 찾기 위한 요약입니다. 세부 요청·응답 모델의 단일 권위는 각 모듈의
컨트롤러와 공개 record입니다.

## 인증

- 사람은 폼 로그인 세션과 CSRF 쿠키를 사용합니다.
- 기계와 IaC는 `DBTOWER_API_TOKEN`을 Bearer 토큰으로 사용합니다. 이 토큰은 `/mcp`에서는 인증되지 않습니다.
- MCP 클라이언트는 `DBTOWER_MCP_TOKEN`(MCP 전용 토큰)을 사용합니다. 이 토큰은 `/mcp`와 MCP 도구가 부르는
  조회·요청 경로에서만 인증됩니다(`io.dbtower.security.McpTokenScope`) — 클라이언트 설정에 넣은 토큰이 새도
  사용자·보안·설정·변경 실행 API는 열리지 않습니다. 미설정이면 기동 시 한 번 만들어 저장하고, 콘솔의 MCP 연동 카드가 보여줍니다.
- 상태 변경은 역할별 권한 검사를 거칩니다. 팀 범위 밖 인스턴스는 존재를 노출하지 않도록
  404를 반환합니다.

현재 주체와 화면 능력은 `GET /api/me`에서 확인합니다.

## 인스턴스 등록

`POST /api/instances`는 새 인스턴스를 등록하고, `PUT /api/instances`는 IaC 재실행을 위한 멱등
등록에 사용합니다. 지원 타입은 `MYSQL`, `POSTGRESQL`, `MSSQL`, `ORACLE`, `MONGODB`입니다.

```bash
curl -X POST http://localhost:8080/api/instances \
  -H "Authorization: Bearer $DBTOWER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "local-mysql",
    "type": "MYSQL",
    "host": "127.0.0.1",
    "port": 13306,
    "dbName": "sample",
    "username": "root",
    "password": "dbtower1234"
  }'
```

TLS가 필요한 대상은 `"useTls": true`를 추가합니다. 인증서 검증은 JVM truststore를 사용하며,
검증을 끄는 옵션은 제공하지 않습니다.

## 관제와 비교

아래 표에서 `{base}`는 `/api/instances/{id}`입니다.

| 메서드와 경로 | 용도 |
|---|---|
| `GET /api/instances` | 등록 인스턴스 목록 |
| `GET {base}/health` | 상태, 버전, 응답 시간 |
| `GET {base}/overview` | 헬스, 복제, 백업을 합친 대상별 운영 종합 |
| `GET {base}/query-stats` | load 기준 쿼리 통계 |
| `GET {base}/slow-queries` | 슬로우 쿼리 |
| `GET {base}/activity` | 활동 시계열 |
| `GET {base}/compare` | 기준 구간과 문제 구간 비교 |
| `GET {base}/wait-events` | Wait Event 분해 |
| `GET {base}/sessions` | 활성 세션과 블로킹 트리 |
| `GET {base}/replication` | 복제 역할, 지연, 내구성 상태 |
| `GET {base}/schema` | 스키마와 테이블 구조 |
| `GET /api/schema-diff` | 두 인스턴스의 스키마 비교 |
| `GET /api/param-diff` | 두 인스턴스의 파라미터 비교 |

## 분석과 자율 진단

| 메서드와 경로 | 용도 |
|---|---|
| `POST {base}/explain` | 실행계획과 규칙 기반 지적 |
| `POST {base}/ai-analysis` | 실행계획 기반 AI 1차 분석 |
| `POST {base}/diagnose` | 읽기 도구를 연쇄 호출하는 자연어 진단 |
| `POST {base}/deep-diagnose` | 실제 실행계획 기반 심층 진단 |
| `POST {base}/index-advisor` | 가상 인덱스 비용 비교 |
| `GET {base}/anomalies` | 동적 베이스라인 이상 신호 |
| `GET {base}/advisors` | 운영 모범규칙 점검 |
| `GET {base}/plan-changes` | 실행계획 변경 이력 |
| `GET {base}/deadlocks` | 최근 데드락 흔적 |
| `GET {base}/slo` | SLO와 에러 버짓 |
| `GET {base}/finops` | 미사용·중복 인덱스 등 낭비 신호 |
| `GET /api/health-score` | 전체 인스턴스 헬스 스코어 |
| `GET /api/backup-freshness` | 전체 인스턴스 백업 신선도 |

기종에 능력이나 원자료가 없으면 값을 꾸미지 않고 `UNSUPPORTED` 또는 미확보 사유를
반환합니다.

## 워크벤치와 변경

| 메서드와 경로 | 용도 |
|---|---|
| `POST /api/workbench/instances/{id}/query` | 읽기 전용 조회, 마스킹, 실행 기록 |
| `POST /api/workbench/instances/{id}/classify` | SQL을 읽기, 변경, 차단으로 분류 |
| `GET /api/workbench/instances/{id}/history` | 내 조회 실행 기록 |
| `GET|POST /api/workbench/instances/{id}/worksheets` | 워크시트 조회와 생성 |
| `POST /api/workbench/worksheets/{id}/assistant` | 실행 권한 없는 AI SQL 제안 |
| `POST /api/workbench/tickets/{id}/dry-run` | 실행 후 롤백하는 사전 검증 |
| `POST /api/workbench/tickets/{id}/execute` | 승인된 티켓 실행 |
| `POST /api/workbench/tickets/{id}/revert` | 행 사본 기반 되돌리기 |
| `POST /api/workbench/tickets/{id}/bulk/start` | 대량 일괄 변경 시작(배치로 쪼개 실행) |
| `GET /api/workbench/tickets/{id}/bulk` | 진행 상태(상태·배치 수·바뀐 행·마지막 적용 키) |
| `GET /api/workbench/tickets/{id}/bulk/batches` | 배치별 기록(키 구간·영향 행 수·그때의 복제 지연), 마지막 50개 |
| `POST /api/workbench/tickets/{id}/bulk/pause` · `resume` · `cancel` | 일시정지·재개·취소(취소는 승인자도 가능) |
| `GET /api/workbench/executions/{id}/workload` | 실행 전후 워크로드 비교 |
| `POST /api/reviews/{id}/cancel` | 변경 요청 취소 |

워크벤치 조회에는 `READ`, 승인 티켓 실행에는 `WRITE` 자격증명을 별도로 등록합니다.
승인 없이 변경 SQL을 실행하는 API는 없습니다.

대량 일괄 변경은 시작 요청이 즉시 돌아오고 진행은 조회로 봅니다 — 수 분~수 시간 걸리는 일을
요청 스레드가 붙잡으면 웹 요청이 타임아웃되고 그 타임아웃이 실행을 끊지도 못합니다.
실행 전 조건(복원 검증된 최근 백업, 단일 기본 키, UPDATE·DELETE, MySQL·PostgreSQL,
승인 시점 예상 대비 2배 이내)을 하나라도 어기면 시작하지 않습니다. 자세한 기준은
[bulk-change-spec.md](bulk-change-spec.md)에 있습니다.

## 운영과 관리

| 메서드와 경로 | 용도 |
|---|---|
| `POST {base}/backup` | 즉시 백업 |
| `POST {base}/backup/verify` | 복원 검증 |
| `PUT {base}/backup-policy` | 백업 정책 설정 |
| `POST {base}/online-ddl` | MySQL gh-ost, 기본 dry-run |
| `POST {base}/sessions/{pid}/kill` | 세션 종료 |
| `GET /api/audit` | 감사 로그 검색 |
| `GET|POST /api/security/users` | 사용자 조회와 생성 |
| `PATCH /api/security/users/{username}/role` | 사용자 역할 변경 |

### AI 운영 작업 (169절)

사람·게이트웨이가 쓰는 경로다. 범위는 호출자 기준으로 걸러진다(범위 밖은 404 — 다른 팀 작업의 존재를 드러내지 않는다).

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| `POST` | `/api/ai-operations` | VIEWER | 작업 접수(202). 같은 `requestId`면 기존 작업을 그대로 돌려준다 |
| `GET` | `/api/ai-operations?limit=30` | VIEWER | 최근 작업 목록(결과 본문 제외) |
| `GET` | `/api/ai-operations/{jobId}` | VIEWER | 작업 상태와 결과(사실·규칙 판정·AI 소견·근거·검증 안 된 내용) |
| `POST` | `/api/ai-operations/{jobId}/cancel` | 요청자 본인 또는 OPERATOR | 진행 중 작업 취소 |
| `POST` | `/api/ai-operations/{jobId}/retry` | OPERATOR | 실패한 작업을 새 시도로 되돌린다(모델을 다시 쓰는 판단이라 운영자) |

접수 본문. 로그인한 사람이 올리면 `requester`·`team`은 무시하고 인증 주체로 채운다 — 본문을 믿으면 요청자를 위조할 수 있다.

```json
{
  "requestId": "slack:Ev123",
  "type": "QUERY_DIAGNOSIS",
  "instanceId": 2,
  "windowMinutes": 30,
  "prompt": "최근 30분 느려진 쿼리 봐줘",
  "trigger": "SLACK",
  "requester": "slack:T1:U1",
  "team": "team-a",
  "replyChannel": "C1",
  "replyThread": "1789496747.218421"
}
```

유형은 `QUERY_DIAGNOSIS`, `REGRESSION_EXPLANATION`, `BACKUP_RISK_REVIEW`, `SLO_RISK_REVIEW`,
`ADVISOR_SUMMARY`, `COST_REVIEW`, `INCIDENT_TRIAGE`, `DB_TEAM_INQUIRY`, `PERIODIC_REPORT`.
상태는 `RECEIVED → AUTHORIZED → COLLECTING → (RETRIEVING) → ANALYZING → VERIFYING → COMPLETED`이고,
실패·취소는 각각 `FAILED`·`CANCELLED`다. 승인 대기 상태는 두지 않는다 — 변경 승인은 review 모듈이 단일 권위다.

#### 릴레이·실행기 (서비스 토큰 전용)

전부 ADMIN이며, 선점 이후 단계는 `X-Lease-Token` 헤더가 맞아야 한다. 사람이 이 경로로 단계를 건너뛰면 사실 수집 없이 소견이 붙는다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/ai-operations/outbox/claim?limit=20` | 미발행 이벤트를 리스로 선점(조건부 UPDATE — 두 릴레이가 같은 행을 가져가지 않는다) |
| `POST` | `/api/ai-operations/outbox/{eventId}/published` | 발행 완료. 선점 토큰이 다르면 409 |
| `POST` | `/api/ai-operations/{jobId}/claim` | 작업 선점(`RECEIVED`에서만). 중복 배달은 409, 범위가 깨졌으면 작업을 실패로 만들고 알림 전용 토큰과 함께 409 |
| `POST` | `/api/ai-operations/{jobId}/facts` | 유형이 정한 범위의 사실·규칙 판정 수집과 저장 |
| `POST` | `/api/ai-operations/{jobId}/retrieving` | 참고 자료 검색 단계 표시 |
| `POST` | `/api/ai-operations/{jobId}/analyze` | 저장된 사실 + 본문의 참고 자료로 모델 소견을 받고, 수치·인용을 대조한 뒤 완료 |
| `POST` | `/api/ai-operations/{jobId}/fail` | 실행 실패 기록 |
| `GET` | `/api/ai-operations/{jobId}/lease-view` | 재배달된 실행기가 이어갈 지점을 찾기 위한 조회 |
| `POST` | `/api/ai-operations/{jobId}/notified` | 알림 완료 기록. 이미 보냈으면 `alreadyNotified: true` |

성공한 실행기·릴레이 호출은 요청 단위 감사에서 빠진다(릴레이가 매초 폴링해 감사 로그를 덮었다 — 169절).
대신 선점·사실 수집·완료·실패는 작업 id와 함께 감사에 남고, 거부(403)·충돌(409)은 그대로 기록된다.

## MCP

Streamable HTTP 진입점은 `POST /mcp`입니다. stdio 실행기는 `scripts/dbtower-mcp.sh`를
사용합니다. MCP는 관제·진단과 워크벤치 요청·조회 도구만 제공하며 변경 실행 도구는
제공하지 않습니다.
