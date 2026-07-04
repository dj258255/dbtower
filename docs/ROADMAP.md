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
| A1 | 인증·인가 — 세션 로그인(BCrypt)+Bearer 토큰, VIEWER/ADMIN, CSRF 쿠키 패턴, fail-closed | 19절 |
| 모듈리스 | Spring Modulith — 모듈 8개 경계를 빌드가 강제, 도입 시 순환 2개 발견·의존 역전으로 해소 | 20절 |

## 완료 (2차 — 품질·문서)

| 항목 | 내용 | 위치 |
|---|---|---|
| 단위 테스트 31건 | 시점 비교 차분·경계, 회귀 감지 4규칙·쿨다운, 백업 명령 주입 방어, MCP 프로토콜, 5기종 판정 규칙 | src/test |
| CI | GitHub Actions gradle test + 실패 리포트 아티팩트 (테스트 전용 H2 설정으로 실 DB 불필요) | .github/workflows/ci.yml |
| 운영 규칙 문서 | digests_size 포화(80% Truncate), digest 길이, PS 가시성 가이드, AAS와 load%, system.profile capped | docs/operations.md |

## 다음 구현 로드맵

핵심 메커니즘의 증명(추상화·시점 비교·회귀 감지·채널·5기종)은 완료. 여기부터는
실제 운영 투입을 향해 계속 구현한다. 세 단계 — A(운영 안전) -> B(DBA 진단 심화) -> C(프로비저닝 연동).

### Phase A — 운영 안전 (완료: 8/8, VERIFICATION 30절)

우선순위·이유·업계 근거. B의 세션 킬 같은 위험 기능이 A의 인증·감사에 의존하므로 A가 먼저다.

| 순위 | 갭 | 왜 필요한가 / 업계 근거 |
|---|---|---|
| 1 | **완료(19절)** 인증·인가 | 세션 로그인(BCrypt)+Bearer 서비스 토큰, VIEWER/ADMIN 분리, CSRF 쿠키 패턴, fail-closed 부트스트랩. 이전엔 콘솔·API·MCP 전부 무인증. DB 접속정보를 다루는 도구라 최우선. Percona PMM은 서비스 계정 + 라벨 기반 접근 제어(환경·기종 단위로 보이는 데이터 제한)를 제공하고, 운영 가이드에서 관리자 수 최소화·신뢰 네트워크 제한을 권고한다 ([PMM Security](https://docs.percona.com/percona-monitoring-and-management/3/admin/security/index.html), [PMM Access Control](https://www.percona.com/blog/pmm-access-control-a-comprehensive-guide-with-use-cases-and-examples/)) |
| 2 | **완료(21절)** 비밀번호 암호화 | AES-256-GCM + enc:v1: 접두사(평문 행 하위 호환), 키 미설정 WARN·오류 키 기동 거부. 다음 단계는 Vault 동적 계정. 이전엔 평문 저장. 최소 애플리케이션 레벨 암호화(AES-GCM + KMS 키), 정석은 Vault database secrets engine — 저장된 정적 비밀번호 대신 짧은 TTL의 동적 계정을 발급·자동 회수하고 접근 주체별 감사가 된다 ([Vault Database Secrets](https://developer.hashicorp.com/vault/docs/secrets/databases)) |
| 3 | **완료(22절)** 스키마 마이그레이션 | Flyway V1 baseline + ddl-auto=validate. Boot 4의 starter 분리 함정 실측. 원래 한계: ddl-auto=update가 기존 CHECK 제약을 갱신하지 않아 기종 추가가 수동 ALTER가 됐다(VERIFICATION 18-3, 직접 밟은 근거). 커뮤니티 표준은 운영에서 ddl-auto=validate + Flyway가 스키마의 단일 권위 ([Flyway + Hibernate Best Practices](https://rieckpil.de/howto-best-practices-for-flyway-and-hibernate-with-spring-boot/)) |
| 4 | **완료(23절)** 스냅샷 보존 | 기본 7일(PI 선례)·1시간 sweep·JPQL 벌크 DELETE·무제한 스위치. 원래 한계: 무한 적재. 선례: AWS Performance Insights의 기본 보존이 7일이고 그 이상은 명시적 선택·과금이다 — 보존기간 + 삭제 배치(또는 시간 파티셔닝)가 기본값이어야 한다 ([PI Pricing & Retention](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PerfInsights.Overview.cost.html)) |
| 5 | **완료(28절)** HA 안전 | ShedLock 분산 락 — 폴러 4종 한 노드만 실행(2노드 실측), usingDbTime 클럭스큐 방어. 쿨다운 외부화는 잔여(정직 명시) |
| 6 | **완료(25절)** 감사 로그 | audit 모듈 — /api 상태변경·로그인·403 월권 기록(인터셉터+인가거부 리스너), Flyway V2. Vault 감사 이점과 짝 |
| 7 | **완료(29절)** 백업 복원 검증 | 3값(VERIFIED/FAILED/UNSUPPORTED), MySQL/PG/Mongo 임시 DB 실복원, MSSQL VERIFYONLY, Oracle UNSUPPORTED 정직 표기. 원격 보관·암호화는 잔여 ([3-2-1-1-0](https://www.datto.com/blog/3-2-1-1-0-backup-rule/)) |
| 8 | **완료(27절)** 최소 권한 계정 | docs/least-privilege.md — 권한 0에서 에러 원문 수집으로 5기종 최소 집합 확정. Mongo clusterMonitor·PG 조용한 저하 등 실측 발견 ([Datadog DBM](https://docs.datadoghq.com/database_monitoring/setup_mysql/selfhosted/)) |
| 9 | 분석 보호장치 | explain에 statement timeout, 대상 DB 과부하 시 수집 백오프 — 진단 도구가 부하 유발자가 되지 않게 |

### Phase B — DBA 진단 심화 (현업 DBA가 매일 쓰는 것들)

시점 비교가 "무엇이 변했나"를 답한다면, B는 "지금 무엇이 막고 있나"와 "어떻게 고치나"를 답한다.
이전에 범위 밖이던 KDMS 본체 기능(Wait Event, Schema Diff)도 여기로 승격.

| # | 기능 | 내용 / 업계 근거 |
|---|---|---|
| B1 | **완료(26절)** Wait Event 분석 | DbmsOperator.waitEvents() 5기종 통합 — MySQL/MSSQL/Oracle 누적, PG 현재 스냅샷, Mongo 대기 큐. MySQL 비활성 instrument·MSSQL idle 필터를 정직하게 표기. REST+MCP(9종)+웹 카드 ([GitLab ASH](https://runbooks.gitlab.com/patroni/wait-events-analisys/)) |
| B2 | **완료(33절)** 블로킹 트리 + 세션 관리 | 5기종 activeSessions/killSession, PG cancel->terminate 2단계 라이브 완주, 블로킹 트리 blockedBy. kill은 ADMIN+감사, MCP 미노출. Mongo blockedBy N/A 정직 표기 |
| B3 | 인덱스 어드바이저 | explain 규칙이 "인덱스가 없다"까지 지적하니, 다음은 "이 인덱스를 만들면 플랜이 이렇게 바뀐다" — PG의 HypoPG로 가상 인덱스를 만들어 실제 생성 없이 플랜 변화를 시뮬레이션, 결과를 AI 1차 분석의 근거로 주입 |
| B4 | 온라인 스키마 변경 연동 | 대형 테이블 ALTER를 락 없이 — gh-ost/pt-online-schema-change 실행·진행률·스로틀을 플랫폼에서 관리. 두 도구 모두 원자적 cut-over와 실행 중 재설정을 지원 ([온라인 스키마 변경 도구 비교](https://planetscale.com/docs/vitess/schema-changes/online-schema-change-tools-comparison)) |
| B5 | 장기 트랜잭션·복제 지연·디스크 증가 알림 | 회귀 감지 폴러의 규칙 확장 — idle-in-transaction 방치(VACUUM 차단), 복제 lag 임계, 디스크 사용 추세 기반 소진 예측(용량 계획) |
| B6 | 파라미터 드리프트 감지 | 같은 역할의 인스턴스 간 설정 diff(max_connections, work_mem 등) — "왜 저 장비만 느리지"의 단골 원인 |
| B7 | Schema Diff | 환경 간(스테이징 vs 운영) 테이블·인덱스 구조 비교 — B3·B4와 묶여 스키마 관리 축 완성. 원인 분석 화면에 인덱스 목록·DDL 표시(KDMS 2단계 갭)부터 시작 |
| B8 | **완료(34절)** 문의 채널 | 분석 결과(쿼리·플랜·규칙·AI·비고) 첨부해 웹훅 전송, alert 모듈(순환 회피), 미설정 sent:false. KDMS 1·2·3단계 완성 |

### Phase C — 프로비저닝 연동 (DB의 탄생부터 관제까지)

지금은 "이미 존재하는 DB"를 수동 등록한다. 현업에서 DB는 IaC로 태어난다 —
태어나는 순간 관제탑에 자동 등록되는 것이 이 층의 목표다.

| # | 환경 | 방식 |
|---|---|---|
| C1 | Kubernetes | CloudNativePG(PostgreSQL)·Percona Operator(MySQL/PG/MongoDB) CR로 클러스터 선언 -> Operator가 프로비저닝·복제·백업·failover 자동화 -> 생성 완료 시 접속 Secret을 읽어 DBTower 등록 API 호출. Operator가 Day-1/Day-2 운영을 맡고 DBTower는 그 위의 쿼리 수준 관제를 맡는 분업 ([CloudNativePG](https://cloudnative-pg.io/), [Percona Operators](https://docs.percona.com/percona-operators/)) |
| C2 | 클라우드 (AWS 등) | Terraform 모듈로 RDS 생성 -> output(엔드포인트·시크릿 ARN)을 등록 API로 전달하는 프로비저닝 파이프라인 |
| C3 | 온프레미스/VM | Ansible 플레이북 — DBMS 설치·모니터링 계정 생성(Phase A8의 최소 권한 grant 목록을 코드화)·exporter 배치·DBTower 등록까지 한 플레이북으로 |
| C4 | 자동 발견·해제 | 등록 API의 자동화 훅 + 제거된 인스턴스 정리 — "등록을 잊은 DB"가 관제 사각지대가 되는 것 방지 |

KDMS가 "DB 생성 자동화"를 본체 기능으로 갖는 이유가 이것이다 — 생성과 관제가 이어져야
플랫폼이고, 끊어져 있으면 도구 모음이다.

## 범위 밖 (여전히 의도적으로 안 한다)

- 파티션 자동 관리, DBaaS 수준의 멀티테넌시·과금·셀프서비스 포털 — 플랫폼 엔지니어링의
  다른 제품 영역. Wait Event·Schema Diff·DB 생성 자동화는 범위 밖에서 로드맵(B·C)으로 승격했다.
  "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.
