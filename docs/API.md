# API 빠른 참조

웹 콘솔, MCP, 자동화 채널은 같은 서비스 코어를 사용합니다. 이 문서는 자주 쓰는 REST
진입점을 빠르게 찾기 위한 요약입니다. 세부 요청·응답 모델의 단일 권위는 각 모듈의
컨트롤러와 공개 record입니다.

## 인증

- 사람은 폼 로그인 세션과 CSRF 쿠키를 사용합니다.
- 기계와 IaC는 `DBTOWER_API_TOKEN`을 Bearer 토큰으로 사용합니다.
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
| `GET /api/workbench/executions/{id}/workload` | 실행 전후 워크로드 비교 |
| `POST /api/reviews/{id}/cancel` | 변경 요청 취소 |

워크벤치 조회에는 `READ`, 승인 티켓 실행에는 `WRITE` 자격증명을 별도로 등록합니다.
승인 없이 변경 SQL을 실행하는 API는 없습니다.

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

## MCP

Streamable HTTP 진입점은 `POST /mcp`입니다. stdio 실행기는 `scripts/dbtower-mcp.sh`를
사용합니다. MCP는 관제·진단과 워크벤치 요청·조회 도구만 제공하며 변경 실행 도구는
제공하지 않습니다.
