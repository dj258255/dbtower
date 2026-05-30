# DBTower 로드맵

목표: 이기종 DBMS 운영 관리 플랫폼(관제탑). 여러 DBMS의 반복 운영 작업(모니터링·진단·백업·알림)을
하나의 추상화 뒤로 자동화해서, 관리 대상 DB가 늘어도 운영 부담이 선형으로 늘지 않게 한다(DBRE).

기준 참고: 당근 DB 밋업 1회 "개발자를 위한 DB 분석 도구와 방법"(KDMS Database Insight) —
분석은 docs/reference/kdms-meetup/ANALYSIS.md.

## 완료

| 단계 | 내용 | 검증 (VERIFICATION.md) |
|---|---|---|
| MVP1 | 이기종 등록 + DbmsOperator 추상화 (MySQL/PostgreSQL/SQL Server) + 헬스체크 | 1절 |
| MVP2 | 시점 비교 — 누적 카운터 스냅샷 구간 차분, QPS 정규화, 신규 쿼리 감지 | 3절 |
| MVP3 | EXPLAIN + 규칙 기반 비효율 분석 (기종별 판단 규칙 + 근거 문서화) | 2절, ai-analysis-rules.md |
| 성능 아크 1 | DriverManager -> HikariCP: 수집 47.1 -> 11.8ms (MySQL, 최대 4.0배) | 6절 |
| 성능 아크 2 | JPA saveAll -> JDBC batchUpdate: 행당 1.51 -> 0.11ms (PG, 13.8배) | 7절 |
| 성능 아크 3 | 도그푸딩 — DBTower로 DBTower 자신 진단, 복합 인덱스로 21.269 -> 0.062ms (343배) | 9절 |
| 성능 아크 4 | max_digest_length 1024 vs 4096 side-by-side — 긴 쿼리 digest 병합 재현·해소 | 10절 |
| 성능 아크 5 | k6 부하: 10 VU 2,832 req/s, P95 5.86ms, 실패 0 | 11절 |
| 확장 1 | 백업 정책 — 추상 정책 + 기종별 실행(mysqldump/pg_dump/BACKUP DATABASE), 보안 보강 | 12·14절 |
| 확장 2 | 모니터링 통합 — Prometheus/Grafana/exporters + 복제 상태 통합 뷰 | 13절 |
| 확장 3 | 쿼리 회귀 자동 감지(4규칙+쿨다운) + Discord/Slack 웹훅 + AI 1차 분석 | 15절 |
| 확장 4 | 웹 UI — 활동 그래프 구간 드래그, 시점 비교(증감·NEW 뱃지), EXPLAIN+AI 분석 화면 | 16절 |
| 확장 5 | MCP 서버 — JSON-RPC 직접 구현, 도구 8종으로 AI 에이전트에 pull형 분석 채널 제공 | 17절 |
| 확장 6 | Oracle·MongoDB 추가 — 5기종. "새 기종 = 구현체 1개" 실측(플랫폼 코드 0줄), 비 JDBC 증명 | 18절 |

## 완료 (2차 — 품질·문서)

| 항목 | 내용 | 위치 |
|---|---|---|
| 단위 테스트 31건 | 시점 비교 차분·경계, 회귀 감지 4규칙·쿨다운, 백업 명령 주입 방어, MCP 프로토콜, 5기종 판정 규칙 | src/test |
| CI | GitHub Actions gradle test + 실패 리포트 아티팩트 (테스트 전용 H2 설정으로 실 DB 불필요) | .github/workflows/ci.yml |
| 운영 규칙 문서 | digests_size 포화(80% Truncate), digest 길이, PS 가시성 가이드, AAS와 load%, system.profile capped | docs/operations.md |

## 현업 전환 갭 — 포트폴리오와 프로덕션 사이에 남은 것

핵심 메커니즘의 증명(추상화·시점 비교·회귀 감지·채널)은 완료했지만, 실제 운영 투입에는
아래가 더 필요하다. "무엇을 안 했는지"를 우선순위·이유·업계 근거와 함께 적어 둔다.

| 순위 | 갭 | 왜 필요한가 / 업계 근거 |
|---|---|---|
| 1 | 인증·인가 (RBAC) | 현재 콘솔·API·MCP 전부 무인증. DB 접속정보를 다루는 도구라 최우선. Percona PMM은 서비스 계정 + 라벨 기반 접근 제어(환경·기종 단위로 보이는 데이터 제한)를 제공하고, 운영 가이드에서 관리자 수 최소화·신뢰 네트워크 제한을 권고한다 ([PMM Security](https://docs.percona.com/percona-monitoring-and-management/3/admin/security/index.html), [PMM Access Control](https://www.percona.com/blog/pmm-access-control-a-comprehensive-guide-with-use-cases-and-examples/)) |
| 2 | 인스턴스 비밀번호 보관 | 현재 메타 DB에 평문 저장. 최소 애플리케이션 레벨 암호화(AES-GCM + KMS 키), 정석은 Vault database secrets engine — 저장된 정적 비밀번호 대신 짧은 TTL의 동적 계정을 발급·자동 회수하고 접근 주체별 감사가 된다 ([Vault Database Secrets](https://developer.hashicorp.com/vault/docs/secrets/databases)) |
| 3 | 스키마 마이그레이션 | ddl-auto=update가 기존 CHECK 제약을 갱신하지 않아 기종 추가가 수동 ALTER가 됐다(VERIFICATION 18-3, 직접 밟은 근거). 커뮤니티 표준은 운영에서 ddl-auto=validate + Flyway가 스키마의 단일 권위 ([Flyway + Hibernate Best Practices](https://rieckpil.de/howto-best-practices-for-flyway-and-hibernate-with-spring-boot/)) |
| 4 | 스냅샷 보존 정책 | 1분 주기 스냅샷이 무한 적재된다. 선례: AWS Performance Insights의 기본 보존이 7일이고 그 이상은 명시적 선택·과금이다 — 보존기간 + 삭제 배치(또는 시간 파티셔닝)가 기본값이어야 한다 ([PI Pricing & Retention](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PerfInsights.Overview.cost.html)) |
| 5 | 다중 인스턴스(HA) | 폴러·회귀 감지 쿨다운이 인메모리라 단일 프로세스 전제. 스케일아웃 시 리더 선출(ShedLock 등) + 쿨다운 상태 외부화(메타 DB/Redis) 필요 |
| 6 | 감사 로그 | 누가 언제 어떤 인스턴스에 explain·백업을 실행했는지 기록. 관리 도구의 기본 요건이자 Vault 동적 계정의 감사 이점과 짝을 이룬다 |
| 7 | 백업 복원 검증 | "테스트해 본 적 없는 백업은 백업이 아니다" — 주기적 복원 리허설과 검증 0-에러 원칙(3-2-1-1-0), 덤프 원격 보관·암호화 ([Veeam 3-2-1](https://www.veeam.com/blog/321-backup-rule.html), [Datto 3-2-1-1-0](https://www.datto.com/blog/3-2-1-1-0-backup-rule/)) |
| 8 | 대상 DB 최소 권한 계정 | 현재 root/sa/system급으로 접속. Datadog DBM도 기종별 전용 읽기 계정과 필요한 grant 목록만 부여하는 방식을 문서화한다 — 기종별 권한 목록 가이드 필요 ([Datadog DBM MySQL Setup](https://docs.datadoghq.com/database_monitoring/setup_mysql/selfhosted/)) |
| 9 | 분석 보호장치 | explain에 statement timeout, 대상 DB 과부하 시 수집 백오프 — 진단 도구가 부하 유발자가 되지 않게 |
| 10 | 문의 채널 (KDMS 3단계) | 분석 결과를 첨부해 Slack 쓰레드를 여는 버튼 — 웹훅 인프라가 있어 소규모 작업. 스키마·인덱스 목록 표시(원인 분석 화면 보강)와 함께 KDMS 흐름의 마지막 조각 |

## 범위 밖 (의도적으로 안 한다)

- Wait Event 분석, 파티션 자동 관리, Schema Diff, DB 생성 자동화 — KDMS 본체 기능이지만
  이 프로젝트의 핵심 서사(추상화 + 시점 비교 + 자동 회귀 감지)와 겹치지 않아 제외.
  "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.
