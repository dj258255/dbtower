# DBTower 로드맵

목표: 이기종 DBMS 운영 관리 플랫폼(관제탑). 여러 DBMS의 반복 운영 작업(모니터링·진단·백업·알림)을
하나의 추상화 뒤로 자동화해서, 관리 대상 DB가 늘어도 운영 부담이 선형으로 늘지 않게 한다(DBRE).

기준 참고: 개발팀의 반복 DB 문의를 셀프서브 분석으로 줄인다는 DBRE 문제의식(업계 사례·AWS Performance Insights).

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
| 단위 테스트 (작성 시점 31건 → 현재 72파일·360메서드) | 시점 비교 차분·경계, 회귀 감지 4규칙·쿨다운, 백업 명령 주입 방어, MCP 프로토콜, 5기종 판정 규칙 외 | src/test |
| CI | GitHub Actions gradle test + 실패 리포트 아티팩트 (테스트 전용 H2 설정으로 실 DB 불필요) | .github/workflows/ci.yml |
| Modulith internal 캡슐화 (2026-07-15) | 14개 모듈 전부 루트=공개 API(서비스·record)만, 구현은 internal/{web,domain,persistence,job}로 은닉 — 타 모듈의 repository·entity 직접 참조 0, ModularityTests가 경계 강제. operator는 record 21종을 model/ 서브패키지(@NamedInterface)로 분리(루트 28→7파일), 이를 위해 spring-modulith-api를 main 의존성에 추가. 규칙은 AGENTS.md "모듈 내부 패키지 규칙" | src/main/java, docs/modules(재생성) |
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
| 5 | **완료(28절)** HA 안전 | ShedLock 분산 락 — 폴러 4종 한 노드만 실행(2노드 실측, 이후 잠금 작업 11종으로 확대), usingDbTime 클럭스큐 방어. 쿨다운 외부화는 잔여(정직 명시) |
| 6 | **완료(25절)** 감사 로그 | audit 모듈 — /api 상태변경·로그인·403 월권 기록(인터셉터+인가거부 리스너), Flyway V2. Vault 감사 이점과 짝 |
| 7 | **완료(29절)** 백업 복원 검증 | 3값(VERIFIED/FAILED/UNSUPPORTED), MySQL/PG/Mongo 임시 DB 실복원, MSSQL VERIFYONLY, Oracle UNSUPPORTED 정직 표기. 원격 보관은 심화 아크에서 완료(55절 — S3 호환 오프사이트), 산출물 암호화는 잔여 ([3-2-1-1-0](https://www.datto.com/blog/3-2-1-1-0-backup-rule/)) |
| 8 | **완료(27절)** 최소 권한 계정 | docs/least-privilege.md — 권한 0에서 에러 원문 수집으로 5기종 최소 집합 확정. Mongo clusterMonitor·PG 조용한 저하 등 실측 발견 ([Datadog DBM](https://docs.datadoghq.com/database_monitoring/setup_mysql/selfhosted/)) |
| 9 | **완료(51절)** 분석 보호장치 | 모든 JDBC 조회에 기본 쿼리 타임아웃(`jdbc()` 헬퍼 단일 지점, `dbtower.query-timeout-seconds`), Mongo 소켓 read 상한도 같은 설정 공유, 심층진단(explain 실행)은 별도 더 짧은 타임아웃. 수집 폴러는 인스턴스별 지수 백오프(연속 실패 시 1→2→4→8→16틱 건너뜀, 1회 성공 즉시 복귀) — 죽은 대상 DB를 매 틱 두드리는 재접속 부하를 막는다. "진단이 부하 유발자가 되면 안 된다"는 원칙. Datadog DBM도 수집 쿼리에 statement timeout을 걸어 모니터링이 대상 부하가 되지 않게 한다 ([Datadog DBM](https://docs.datadoghq.com/database_monitoring/)) |

### Phase B — DBA 진단 심화 (완료: B1~B8, VERIFICATION 41절)

시점 비교가 "무엇이 변했나"를 답한다면, B는 "지금 무엇이 막고 있나"와 "어떻게 고치나"를 답한다.
이전에 범위 밖이던 레퍼런스 본체 기능(Wait Event, Schema Diff)도 여기로 승격.

| # | 기능 | 내용 / 업계 근거 |
|---|---|---|
| B1 | **완료(26절)** Wait Event 분석 | DbmsOperator.waitEvents() 5기종 통합 — MySQL/MSSQL/Oracle 누적, PG 현재 스냅샷, Mongo 대기 큐. MySQL 비활성 instrument·MSSQL idle 필터를 정직하게 표기. REST+MCP(9종)+웹 카드 ([GitLab ASH](https://runbooks.gitlab.com/patroni/wait-events-analisys/)) |
| B2 | **완료(33절)** 블로킹 트리 + 세션 관리 | 5기종 activeSessions/killSession, PG cancel->terminate 2단계 라이브 완주, 블로킹 트리 blockedBy. kill은 ADMIN+감사, MCP 미노출. Mongo blockedBy N/A 정직 표기 |
| B3 | **완료(35절)** 인덱스 어드바이저 | PG HypoPG 가상 인덱스로 Cost 320->122 실측(ADVISED), 실제 인덱스 미생성. 타 기종 UNSUPPORTED 정직. SELECT 전용+식별자 방어 |
| B4 | **완료(38절)** 온라인 스키마 변경 | gh-ost 오케스트레이션(MySQL), 기본 dry-run·실행은 ADMIN confirm, 비밀번호 0600 conf. 라이브 noop+execute 완주. 비 MySQL UNSUPPORTED |
| B5 | **완료(39절)** 운영 알림 확장 | OpsAlertDetector(HA): 장기 idle-in-transaction·복제 지연·수집 정지. operator 변경 0(기존 메서드 재사용), 라이브 감지 확인 |
| B6 | **완료(40절)** 파라미터 드리프트 | parameters() 5기종+diff, 민감값 마스킹, ADMIN 제한. 라이브 PG 368·MySQL 623개 |
| B7 | **완료(36절)** Schema Diff | describeSchema() 5기종, 추가/삭제/변경 3분류, 기종혼합·상한 경고, Mongo 스키마리스 처리. MCP 12종(D5 partitions 추가로 현재 13종) |
| B8 | **완료(34절)** 문의 채널 | 분석 결과(쿼리·플랜·규칙·AI·비고) 첨부해 웹훅 전송, alert 모듈(순환 회피), 미설정 sent:false. 레퍼런스 1·2·3단계 완성 |

### Phase C — 프로비저닝 연동 (완료: VERIFICATION 42~45절)

지금은 "이미 존재하는 DB"를 수동 등록한다. 현업에서 DB는 IaC로 태어난다 —
태어나는 순간 관제탑에 자동 등록되는 것이 이 층의 목표다.

| # | 환경 | 방식 |
|---|---|---|
| C1 | **완료(43절)** Kubernetes | kind+CloudNativePG 1.24로 e2e 완주 — Cluster CR→프로비저닝→-app Secret→등록 Job(PUT)→health up(PostgreSQL 16.4). infra/k8s/ |
| C2 | **완료(44절)** 클라우드 | Terraform(OpenTofu) RDS 모듈 + local-exec 등록, validate 통과. apply는 AWS 자격증명 필요라 미실행(정직). infra/terraform/ |
| C3 | **완료(44절)** 온프레미스/VM | Ansible 플레이북 e2e 완주 — 최소권한 계정 생성+등록, 멱등 재실행 changed=0. infra/ansible/ |
| C4 | **완료(42절)** 멱등 등록 | PUT upsert — IaC 재실행 안전(같은 이름 갱신, 중복 0). 셋 다 이 종점을 공유 |

레퍼런스가 "DB 생성 자동화"를 본체 기능으로 갖는 이유가 이것이다 — 생성과 관제가 이어져야
플랫폼이고, 끊어져 있으면 도구 모음이다.

## Phase D — 자율 진단 (사람이 모는 대시보드에서, 스스로 보는 관제탑으로)

> **구현 담당: Opus. 이 절은 착수 명세다.** 아래 각 항목은 (1) 실존 상용 제품 근거, (2) 재활용할
> 기존 DBTower 자산, (3) 검증 기준을 명시한다. Phase A~C와 같은 원칙: 이기종 5기종 통합,
> 읽기·진단 중심(정체성 이탈 금지), 모든 완료는 라이브 실측 근거, 새 능력은 DbmsOperator 메서드
> 1개 추가 또는 기존 재사용, 허위 금지, 이모지·커밋 AI 언급 금지.

### 방향의 근거 (웹서칭 종합)

- 2026 DBA 설문: 알림 피로 75%(절반이 "심각"), 상시 파이어파이팅·번아웃, "50ms→800ms 조용한
  저하" 미탐지, 통합 모니터링은 40%뿐. → **더 많은 대시보드가 아니라, 스스로 이상을 잡아 묶어주는 자율화**가 고통의 해독제.
- 실존 제품이 이미 이걸 판다: **AWS DevOps Guru for RDS**(DB Load 베이스라인 학습→이상 감지→wait event
  조사 방향 제시, proactive/reactive), **Percona PMM Advisors**(Config/Perf/Query/Security 자동 점검을
  24h 배경 실행, "고치는 법까지"), **pganalyze**(Index/Query Advisor, "AI-assisted but developer-driven").
- 자율 AI 진단 사례와 일치: "CPU 100% 알람 10분
  지속 → AI가 모니터링+Wait Event 동시 조회 → 원인 진단". D3가 이것.
- 현대오토에버 DBA 우대사항 "문제 근본 원인 발견·해결 경험"에 정면 대응.

### 왜 변경 관리(SQL Review/승인)를 Phase D에 안 넣나 (정직한 범위 결정)

gh-ost·pt-osc·goInception이 전부 MySQL 전용이라 이기종 정체성과 충돌하고, 쓰기 경로 거버넌스는
읽기·진단이라는 DBTower DNA와 다른 카테고리다. 인상적이지만 초점을 흐린다 → "범위 밖" 유지.

| # | 기능 | 실존 제품 근거 | 재활용 자산 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|---|---|
| D1 | **완료(46절)** 이상 자동 감지 (베이스라인) | AWS DevOps Guru for RDS (DB Load 이상 감지) | QuerySnapshot 이력, ComparisonService(시점 비교), RegressionDetector(폴러·쿨다운·ShedLock) | 스냅샷 이력으로 인스턴스·쿼리별 **요일×시간대 베이스라인**(평균/표준편차 또는 분위수) 계산 → 폴러가 현재값을 베이스라인과 비교해 z-score/분위수 이탈을 이상으로 판정(고정 임계 +200%를 대체·보완). 신규 인스턴스는 데이터 부족 시 학습 중 표기. `insight` 모듈에 BaselineService + 이상 판정, `alert`가 소비 | 부하 스크립트로 평소→급증 만들고, 고정 임계 없이 "평소 대비 이탈"로 감지되는지 라이브. 단위: 베이스라인 계산·이탈 판정·데이터 부족 처리 |
| D2 | **완료(46절)** Advisors 자동 점검 | Percona PMM Advisors (Config/Perf/Query/Security 24h 스윕) | operations.md·least-privilege.md의 실측 규칙, parameters()(B6)·tableStats·describeSchema()(B7)·slowQueries | 규칙을 코드 Advisor로: digests_size 80% 포화, 스냅샷 보존 미설정, 위험 파라미터값(max_connections 과소 등), 미사용/중복 인덱스 후보, 권한 과다 계정, 통계 미수집 테이블. 5기종별 적용 가능한 것만(기종 무관은 UNSUPPORTED 표기). `advisor` 신규 모듈, 일일 스윕(@Scheduled+@SchedulerLock) + REST/웹 카드(심각도별) | 실 5기종에 스윕 돌려 실제 지적 나오는지(예: 스냅샷 보존 미설정 인스턴스 flag). 각 Advisor 단위 테스트(위반/정상) |
| D3 | **완료(47절)** 자연어 근본원인 진단 (AI 에이전트) | 레퍼런스 "CPU 100%→AI가 도구 연쇄 진단", pganalyze AI-assisted | McpProtocolHandler(도구 12종), AiAnalyzer(claude CLI/SDK 백엔드), ai-analysis-rules.md | "왜 느려졌어?" 같은 질문 → AI가 **여러 MCP 도구(compare·wait_events·sessions·explain)를 스스로 연쇄 호출**해 근본원인 서술. 단발 분석(현 AiAnalyzer)을 **도구 사용 루프**로 승격. 판단 기준 문서를 시스템 프롬프트로(허위 금지·근거 없으면 모른다). 웹 콘솔에 질문 입력창, 답변에 사용한 도구·근거 표시 | 실제 부하 상황에서 질문→AI가 도구 2개 이상 엮어 원인(예: 신규 LIKE 풀스캔+wait io) 답하는지 라이브. 근거 없는 질문엔 "모른다" 확인 |
| D4 | **완료(48절)** DB SLO / 에러 버짓 | Google SRE, DBRE(p95/p99·error budget·burn rate) | activity/query-stats(평균), health(가용성), 스냅샷 이력, **D4a의 백분위** | 인스턴스·핵심 쿼리별 SLI(p95/p99 레이턴시·가용성) 정의 → SLO 목표 → **에러 버짓 소진·번인 레이트** 대시보드. p95/p99는 D4a가 공급(기종별 가용성 다름). "인프라 지표(CPU) 아니라 사용자 경험 지표"(SRE 원칙) | 이력으로 SLI·SLO 대비 버짓 소진율 표시 라이브. 단위: SLI 계산·버짓 산식 |
| D4a | **완료(46절)** 레이턴시 백분위 (p95/p99) — 이기종 정직 | MySQL QUANTILE_95/99 컬럼, MongoDB profile 원샘플, "same metric different source"(추상화 논지) | QueryStat/QuerySnapshot 확장, 각 기종 operator | `latencyPercentiles()` 새 능력. **MySQL 8.0+**: events_statements_summary_by_digest의 QUANTILE_95/99/999 컬럼(피코초→ms). **MongoDB**: system.profile의 op별 원시 millis로 백분위 직접 계산. **PostgreSQL**: pg_stat_statements에 백분위 없음 → mean+1.645×stddev 근사를 "추정치(정규분포 가정, 꼬리 무거우면 과소평가)"로 명확히 라벨. **MSSQL/Oracle**: UNSUPPORTED(min/max/mean만). **한계 정직**: MySQL QUANTILE은 리셋 이후 누적이라 "윈도우 p95"가 아님 — 1단계는 누적 p95/p99, 2단계 옵션으로 events_statements_histogram_by_digest 두 스냅샷 차분 → 진짜 윈도우 백분위(히스토그램 수집 필요) | MySQL에서 실제 QUANTILE_95 값 반환·Mongo profile 계산값 라이브. PG는 "추정" 라벨 확인, MSSQL/Oracle UNSUPPORTED. 단위: 백분위 계산·근사 라벨·누적 한계 |
| D5 | **완료(47절)** 파티션 조회 (Partition Inventory) | 레퍼런스 MCP 6기능 중 하나 | describeSchema()(B7) 확장, DbmsOperator | 기종별 파티션 목록·범위·크기 조회(MySQL/PG/Oracle 파티셔닝, Mongo는 샤딩/UNSUPPORTED). 레퍼런스 갭 중 마지막 조각. **자동 관리(생성·삭제)는 범위 밖** — 조회만 | 파티션 있는 테이블로 목록 반환 라이브(없으면 빈 결과·UNSUPPORTED 정직) |
| D6 | **완료(48절)** 비용/효율 인사이트 (FinOps) | AWS FinOps agent, Mydbops(미사용 인덱스로 34~43% 절감) | tableStats(크기), describeSchema()(인덱스), parameters() | 미사용/중복 인덱스 후보, 테이블 bloat, 오버프로비저닝 신호(연결 수 대비 max_connections 등)를 "낭비 후보"로. 실제 클라우드 과금 연동은 범위 밖(자격증명), 신호 제시까지 | 실 DB에서 미사용 인덱스 후보 실제 검출 라이브. UNSUPPORTED 정직 |
| D7 | **완료(47절)** 백업 신선도·커버리지 뷰 | 3-2-1 백업 원칙, DBA 일일 점검(모든 DB가 최근 백업됐나) | BackupRun 이력(확장1)·verifyRestore(A7) | 인스턴스별 마지막 백업 시각·복원 검증 상태·경과 시간을 한 화면에. 임계(예: 24h 초과 미백업) 넘으면 경보(B5 폴러에 규칙 추가). "백업했다"가 아니라 "지금 백업이 최신이고 복원 가능한가"를 상시 가시화 | 인스턴스별 마지막 백업·검증 상태 정확히 표시, 오래된 인스턴스 flag 라이브 |
| D8 | **완료(50절)** 통합 헬스 스코어 | 관측성 카테고리(자동 우선순위·클러스터), 설문(40%만 통합) | D1(이상)·D2(advisor)·D4(SLO)·health·D7(백업)을 합산 | 인스턴스마다 흩어진 신호(이상 개수·advisor 심각도·SLO 버짓·백업 신선도·health)를 **하나의 점수/등급**으로. 대시보드 상단에 5기종 전체를 한눈에, 나쁜 순 정렬. "40% 통합" 고통에 직접 대응 — 어디부터 볼지 기계가 우선순위 매김 | 여러 신호가 점수로 합산·정렬되는지, 문제 인스턴스가 상단에 오는지 라이브. 단위: 점수 산식 |
| D9 | **완료(50절)** 심층 원인 진단 (왜 인덱스를 못 타나)** | 공통 열쇠=추정 vs 실제 행수 괴리. [PG mis-estimate](https://pganalyze.com/docs/explain/insights/mis-estimate)·[Oracle E/A-Rows](https://jonathanlewis.wordpress.com/2016/05/05/e-rows-a-rows/)·[MSSQL](https://www.sqlservercentral.com/articles/why-your-index-isnt-being-used-reading-execution-plans-to-find-the-real-culprit)·[Mongo executionStats](https://www.mongodb.com/docs/manual/tutorial/analyze-query-plan/) | explain()·RuleBasedAnalyzer·describeSchema·D2 stale-statistics·D3 자연어 진단. **상세 명세·기종별 메커니즘·근본원인 5종 표는 docs/ai-analysis-rules.md "심층 원인 규칙 (D9)" 절 — 그게 구현 스펙이다(웹서칭 검증됨)** | `explainAnalyze(sql)` 새 능력(실제 실행 계획): MySQL 8.4 `EXPLAIN ANALYZE FORMAT=JSON`(actual_* 필드), PG `EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)`, Oracle `/*+ gather_plan_statistics */`+`DISPLAY_CURSOR('ALLSTATS LAST')`(같은 커넥션 필수·SELECT_CATALOG_ROLE로 충분), MSSQL `SET STATISTICS XML ON`+별도 결과셋(plain Statement+getMoreResults — PreparedStatement 함정), Mongo `executionStats`. 카디널리티 괴리 감지: 추정 vs 실제 10배+ 최하위 노드 지목 — **PG·MySQL actual rows는 loops당 평균이라 총량은 loops 곱(오독 함정)**. 근본원인 규칙 매칭(형변환·컬럼함수·통계노후·선택도·선두누락). 안전: SELECT 전용 + 타임아웃(MySQL MAX_EXECUTION_TIME 힌트·PG SET LOCAL statement_timeout·Mongo maxTimeMS·나머지 setQueryTimeout). 권한·기종 불가는 UNSUPPORTED | 실 5기종: 형변환·풀스캔 쿼리로 괴리·근본원인 지적 라이브, loops 곱셈 정확성, 타임아웃 동작, UNSUPPORTED. 단위: 파싱·괴리판정·규칙매칭 |

**구현 순서 권장 (Opus):** D1(이상 감지) → D2(Advisors) 를 먼저 — 둘이 "자율화"의 뼈대이고 기존
폴러/규칙 문서를 그대로 승격한다. 이어 D3(자연어 진단)로 채널·AI 자산을 루프로 엮으면 "스스로 보고
설명하는 관제탑"이 완성. **D8(통합 헬스 스코어)은 D1·D2·D4·D7이 신호를 내놓은 뒤 맨 마지막에** —
흩어진 신호를 합산하는 것이라 앞 항목들에 의존한다. D4·D5·D6·D7은 그 사이 선택 확장.
병렬 시 D1·D3(insight/alert·mcp)과 D2·D5·D6(advisor·operator), D7(backup)로 나누면 파일 충돌이 적다.
D8은 단독·마지막.

**정체성 가드레일:** Phase D의 모든 기능은 **읽고 판단**한다(쓰기·변경·승인 없음). 대상 DB를 바꾸지
않고, 5기종 통합을 유지하며, 못 하는 기종은 UNSUPPORTED로 정직하게 표기한다. 이 선을 넘으면(예: 자동
인덱스 생성, SQL 승인 워크플로) 그건 다른 제품이다.

## Phase E — 셀프호스트 제품화 (완료: VERIFICATION 52절)

SaaS 는 이 제품에 안 맞는다 — 대상 DB 자격증명 수탁, 사설망 도달, 멀티테넌시, 비용 네 가지가 막는다.
그래서 Grafana/PMM 처럼 **셀프호스트**로 간다: 사용자가 자기 인프라에 도구를 띄우고 자기 DB 를 붙인다.

| 항목 | 내용 |
|---|---|
| 앱 컨테이너화 | 멀티스테이지 Dockerfile — 빌드 스테이지에서 `bootJar`(plain jar 회피), 런타임은 JRE + 비루트 + actuator HEALTHCHECK |
| 배터리 포함 | 백업/복원이 shell-out 하는 클라이언트 번들 — mysqldump·pg_dump(PGDG 16, 스큐 방지)·mongodump. MSSQL(서버사이드 T-SQL)·Oracle(UNSUPPORTED)은 불필요라 미포함 |
| 컨테이너 프로파일 | `application-docker.yml` — 로컬 데브의 docker-exec 백업 명령을 "대상에 직접 네트워크 접속" 형태로 덮어씀(로컬 설정 불변) |
| 원커맨드 셀프호스트 | `docker-compose.app.yml`(앱 + 전용 메타 DB) + `.env.example` — `docker compose -f docker-compose.app.yml up -d` |
| 이미지 게시 | `release.yml` — `vX.Y.Z` 태그 push 시 GHCR 게시(semver 자동 태깅, 게시 전 테스트 게이트). 게시는 사용자가 태그를 push 할 때만 |

## 심화 아크 — 완주 후 깊이 파기 (완료: VERIFICATION 54~56절)

새 축을 늘리지 않고(정체성 유지) 기존 축의 정직한 잔여 세 개를 팠다.

| 항목 | 축 | 내용 |
|---|---|---|
| TLS 강제 접속 | 셀프호스트 실사용 | `useTls` 옵션 — 기종별 반영(MySQL REQUIRED·PG require·MSSQL encrypt=true·Oracle tcps·Mongo ssl). 인증서 검증 우회 옵션은 일부러 없음 — "TLS를 켰다"는 착각만 주는 보안 구멍이라서 |
| 백업 원격 보관 | 3-2-1 완성 | 성공 백업을 S3 호환(AWS·MinIO·R2)에 업로드. 업로드 실패는 백업 실패가 아니라 별개 사실(remoteLocation null)로 기록 |
| 플랜 변경 감지 | 진단의 마지막 구멍 | "쿼리는 그대로인데 느려짐 = 플랜 플립"(pganalyze·PMM 선례). 회귀 감지된 쿼리만 계획 shape(노드·인덱스만, 추정치 제거) 해시 비교 — 정규화 텍스트($1)는 PG 16 GENERIC_PLAN으로, 타 기종은 플레이스홀더 없는 것만(지어내지 않음) |

## 심화 아크 2 — 문의 채널 진단 맥락 강화 (B8 심화)

> **구현 완료** (VERIFICATION 65절). ReferencedTables 추출기 + ReferencedSchemaService(describeSchema
> 재사용·교집합, 인덱스 중심 요약+행수 "≈"·notFound 정직) + 문의 embed/본문 첨부 + 사이트 상세 패널.
> 조인 테이블 포함, 데모 PG로 라이브 확인. 스코프 조정: 전용 describeTables(IN-조회)·임베드→파일→딥링크는
> describeSchema 재사용/인라인으로 대체(대부분 스키마 상한 안, 밖은 notFound) — 아래 원 명세는 기록으로 보존.
>
> **구현 담당: Opus. 착수 명세.** B8(문의 채널)은 쿼리·플랜·규칙·AI·비고까지 보내지만 정작
> 진단의 핵심인 **참조 테이블 구조(컬럼·인덱스)와 조인 구성**이 빠져 있다. DB팀은 "느리다+플랜"만 받고
> "조인 컬럼에 인덱스가 있나·타입이 맞나"를 판단할 재료가 없다. 이 아크가 그 구멍을 메워 문의를 현업
> 실사용 수준으로 올린다(사이트 상세 패널도 동일 표시). 원칙 동일 — 읽기 전용, 5기종 공통, 정직 표기.

### 방향의 근거 (웹서칭 종합, 2026-07-14)

- 슬로우 쿼리의 단골 근본원인은 **WHERE/JOIN/ORDER BY 컬럼의 인덱스 부재**와 **조인 컬럼 타입 불일치
  (캐스팅→인덱스 무력화→풀스캔)** — 구조를 봐야 판정된다 ([Percona 트러블슈팅](https://www.percona.com/blog/troubleshooting-common-mysql-performance-issues/), [DBA Guide 2025](https://medium.com/@jholt1055/database-query-optimization-the-complete-dba-guide-to-identifying-and-fixing-slow-queries-in-2025-80cf25c1c7bb)).
- 옵티마이저 판단은 **카디널리티(행수)** 에 달렸다 — "인덱스 없음" 지적도 "이 테이블 500만 행"이 붙어야
  설득력이 선다 ([SQL Server Statistics](https://learn.microsoft.com/en-us/sql/relational-databases/statistics/statistics?view=sql-server-ver17)).
- 참조 객체는 **information_schema 타겟 조회**가 정석(= SHOW INDEX FROM을 테이블명으로 필터). 상용
  index-audit·pganalyze도 "쿼리가 친 테이블의 기존 인덱스"를 스코프로 보여준다 ([pganalyze](https://pganalyze.com/postgres-analyze-query-performance)).
- 테이블명은 정규식 FROM/JOIN 파싱이 취약(서브쿼리·CTE·따옴표) — **DB 존재 검증을 안전망**으로 둔다
  (없는 이름은 조회 결과가 비어 자동 탈락) ([Parsing table names from SQL](https://grisha.org/blog/2016/11/14/table-names-from-sql/)).
- Discord 임베드 필드는 1024자·총 6000자 한도 — 초과 콘텐츠는 **파일 첨부가 정석**(웹훅 25MB)
  ([Discord Embed Limits](https://discord-webhook.com/en/blog/discord-webhook-embed-limits/)).

### 3축 결정 (잠금)

| 축 | 선택 | 탈락안 / 이유 |
|---|---|---|
| 테이블 식별 | **타겟 IN-조회** — SQL에서 FROM/JOIN 추출 → 파라미터 바인딩 IN-절로 참조 테이블만 조회 | 전체 스냅샷+필터는 SchemaSupport 200 cap에 참조 테이블 누락 위험(탈락). 플랜 추출(5기종 포맷 파싱)은 더 권위 있으나 향후 보조로 유보 |
| 담을 내용 | **인덱스 중심 + 행수(경량 통계)** | 전체 DDL(SHOW CREATE/pg_dump)은 SchemaSnapshot이 "DDL 재현 아님"으로 선 그은 지점 — 제외 |
| 전달 | **인라인 임베드 → 초과 시 파일 첨부 → 딥링크 곁들임** | 임베드 단독 절단은 다중 테이블에서 정보 손실 |

### 착수 명세 (Opus)

| # | 조각 | 재활용 자산 | 구현 명세 | 검증 기준 |
|---|---|---|---|---|
| I1 | describeTables(참조명 집합) | describeSchema 5기종 SQL, SchemaSupport.build | 기존 카탈로그 SQL에 `AND table_name IN (?,…)` — 이름 개수만큼 플레이스홀더 동적 생성 + **파라미터 바인딩**(concat 금지). ReferencedSchema record(tables·notFound·truncated) 반환. 행수는 카탈로그 추정치(MySQL TABLE_ROWS·PG reltuples·MSSQL dm_db_partition_stats·Oracle num_rows·Mongo estimatedDocumentCount), "≈" 표기 | 5기종 라이브 조회, IN-바인딩 주입 안전 단위 테스트, 없는 이름 notFound 표기 |
| I2 | referencedTables(sql) 추출기 | 신규 순수 함수(insight 또는 analysis) | FROM/JOIN 뒤 식별자, 스키마 수식자·따옴표·대소문자 정규화. best-effort + DB 검증 안전망(CTE·별칭은 조회 공집합으로 자동 탈락) | 조인·서브쿼리·CTE·수식자·따옴표·대소문자 단위 테스트 |
| I3 | 인덱스 중심 요약 | InquiryService.buildEmbed/format | 테이블별 행수·PK·인덱스(조인/필터 컬럼 강조)·컬럼(타입+NULL 여부, 타입 불일치 진단용). notFound 정직 표기 | 다중 테이블 포맷·경계 |
| I4 | 전달: 임베드→파일→링크 | WebhookNotifier.sendEmbed | 총량 계산 후 1024/6000 초과 시 멀티파트 파일 첨부(schema-\<inst\>.txt), 임베드 하단 딥링크(신규 DBTOWER_BASE_URL, 미설정 시 생략), Slack/미설정 텍스트 폴백 | 절단→첨부 전환 경계, 링크 유무 |
| I5 | 사이트 상세 패널 | app.js 상세 패널, index.html | POST /api/instances/{id}/referenced-schema → 섹션 렌더(반드시 esc 경유), 문의 payload에 동봉해 사이트=Discord 내용 일치(기존 plan/findings 패턴) | 라이브 렌더, XSS 방어 |

### 정직성·범위 (불변식)

- 대상 DB 읽기 전용 카탈로그 조회만, IN-절 파라미터 바인딩(명령 주입 방어선), 시스템 스키마 제외·민감값 마스킹 승계
- 조회 실패/권한 없음은 빈 값 뭉개기 금지 → notFound. 행수는 "≈" 추정치임을 명시(실측인 척 금지)
- 플랜 기반 추출(5기종 플랜 포맷 파싱)은 이번 범위 밖 — 뷰 간접 참조 등 누락은 notFound로 노출
- 검증: docker compose 5기종 실측 + docs/VERIFICATION.md 절 추가(명령·출력·Discord 스크린샷)

## 심화 아크 3 — 테이블 상세 정보 레퍼런스 패리티 (착수 명세)

> **구현 완료** (VERIFICATION 66절). tableDetail 5기종(MySQL·Oracle=NATIVE DDL, PostgreSQL·MSSQL=RECONSTRUCTED, MongoDB=컬렉션 JSON), 인덱스 카디널리티·타입, 크기 통계, 주입 방어. POST /api/instances/{id}/table-detail + 상세 패널 아코디언. 데모 MySQL·PG 라이브. 아래 명세는 기록.
>
> **구현 담당: Opus. 착수 명세(미착수).** 레퍼런스의 "테이블 상세 정보" 화면(스키마 정보=CREATE TABLE 전문,
> 기본 통계=엔진·행수·데이터/인덱스 크기·평균 행 길이·생성 시각, 인덱스 정보=컬럼·타입·카디널리티)을
> 5기종에서 재현한다. 심화 아크 2(문의 테이블 구조, 65절)의 "린 요약"을 풀 상세로 승격하는 다음 계단.
> 레퍼런스 대비 현재 갭: 데이터/인덱스 크기·평균 행 길이 표시(원천은 tableStats에 있음), 인덱스
> 카디널리티·타입(없음), CREATE TABLE 전문(없음 — describeSchema는 의도적으로 "요약"). 원칙:
> 읽기 전용, "값과 출처의 정직"(D4a 레이턴시 백분위와 동일 — NATIVE/RECONSTRUCTED/UNSUPPORTED 구분,
> 추정을 원문인 척하지 않는다), 식별자 주입 방어.

### 데이터 모델 (operator/model, @NamedInterface)

```java
record TableDetail(String table, String engine, long rowCount, long dataBytes, long indexBytes,
                   long avgRowBytes, String createdAt, String ddl, DdlSource ddlSource,
                   List<IndexDetail> indexes, String note)
// DdlSource: NATIVE(엔진 원문) / RECONSTRUCTED(카탈로그 재구성 근사) / UNSUPPORTED
// IndexDetail(name, columns, unique, String type, Long cardinality)  — 미확보는 null(정직)
// 미확보 수치는 -1, TableDetail.unsupported(table, note) 정적 팩토리
```

### 인터페이스 + 주입 방어

- `DbmsOperator.tableDetail(String tableName)` — default는 UNSUPPORTED 반환(새 기종 하위호환).
- **식별자 방어(필수)**: SHOW CREATE TABLE·DBMS_METADATA처럼 파라미터 바인딩이 불가한 자리에
  테이블명이 들어가므로, `internal/TableDetailSupport.requireIdentifier` — `^[A-Za-z0-9_$#]{1,128}$`
  밖이면 OperatorException(공백·따옴표·세미콜론 전부 거부, renderCommand 방어와 같은 원칙).
  통계 조회(information_schema류)는 가능한 한 파라미터 바인딩.
- `TableDetailSupport.reconstructDdl(table, columns, pkColumns, indexDefs)` — SHOW CREATE TABLE이
  없는 기종용 근사 CREATE TABLE 조립(컬럼 name/type/NOT NULL/DEFAULT + PK + 인덱스 정의 병기).
  FK/CHECK/트리거/파티션은 담지 않으며 호출자는 반드시 RECONSTRUCTED로 표기(원문 위장 금지).

### 기종별 소스 (전부 읽기 전용)

| 기종 | 기본 통계 | 인덱스 타입·카디널리티 | DDL |
|---|---|---|---|
| MySQL | information_schema.TABLES — ENGINE·TABLE_ROWS·DATA_LENGTH·INDEX_LENGTH·AVG_ROW_LENGTH·CREATE_TIME (바인딩: schema+table). 추정치 note | information_schema.STATISTICS — INDEX_TYPE·CARDINALITY. **함정**: 복합 인덱스는 SEQ_IN_INDEX 위치별 누적 카디널리티라 마지막(최대)값이 인덱스 전체 고유값 | `SHOW CREATE TABLE \`db\`.\`table\`` (식별자 검증 후 백틱) → **NATIVE** |
| PostgreSQL | pg_class(reltuples)+pg_table_size/pg_indexes_size(바인딩). avgRow=data/rows 계산. 생성 시각 없음(PG는 저장 안 함 — null+note) | pg_index+pg_am(amname=btree 등). 카디널리티는 **선두 컬럼 pg_stats.n_distinct 추정** — 음수면 -비율×reltuples 환산, note에 "추정" 명시 | 컬럼(information_schema.columns)+PK(pg_constraint contype='p')+인덱스(pg_indexes.indexdef는 원문) 재구성 → **RECONSTRUCTED** |
| SQL Server | sys.tables(create_date)+sys.dm_db_partition_stats(행수·페이지×8192, tableStats와 동일 산식) | sys.indexes(type_desc·is_unique)+sys.index_columns. 카디널리티 **null**(DBCC SHOW_STATISTICS는 무겁고 기본 노출 아님 — 미확보 정직) | INFORMATION_SCHEMA.COLUMNS+KEY_COLUMN_USAGE 재구성 → **RECONSTRUCTED** |
| Oracle | user_tables(num_rows·avg_row_len)+user_segments(bytes, tableStats 산식 재사용)+user_objects.created. num_rows는 DBMS_STATS 이후만(기존 note 승계) | user_indexes — index_type·uniqueness·**DISTINCT_KEYS(네이티브 카디널리티)**+user_ind_columns | `SELECT DBMS_METADATA.GET_DDL('TABLE', ?) FROM dual` — **함수 인자라 바인딩 가능**, CLOB→문자열, 대문자 변환 → **NATIVE** |
| MongoDB | $collStats storageStats — count·size·totalIndexSize·avgObjSize(tableStats 재사용) | listIndexes — key 스펙에서 컬럼, unique 플래그, 타입은 key 값이 문자열이면 그 값(text/hashed/2dsphere), 숫자면 btree. 카디널리티 null | listCollections options(validator 포함)+인덱스 정의 JSON pretty print → NATIVE(단, "스키마리스 — 컬렉션 옵션·인덱스 정의" note) |

### 채널·프론트

- REST: `POST /api/instances/{id}/table-detail` (body: `{table}`) — 인증 사용자(읽기 진단, VIEWER 가능).
  기존 `referenced-schema` 응답의 테이블 목록에서 테이블명을 받아 개별 조회하는 흐름.
- 상세 패널: "관련 테이블 구조" 각 테이블을 레퍼런스처럼 **접이식 아코디언**으로 — 펼치면
  ① 스키마 정보(ddl 코드블록, RECONSTRUCTED면 "카탈로그 재구성(근사)" 배지) ② 기본 통계(엔진·행수·
  데이터/인덱스 크기·평균 행 길이·생성 시각 — 사람 단위 포맷) ③ 인덱스 정보 카드(이름·UNIQUE 뱃지·
  컬럼·타입·카디널리티, 미확보는 "—"). 동적 값 전부 esc() 경유.
- 문의 첨부: 기존 compact 요약에 크기(데이터/인덱스)·카디널리티만 추가(Discord 1024자 한도 안에서 —
  DDL 전문은 embed에 넣지 않는다, 패널 전용).
- MCP: 기존 schema 도구가 있으므로 table_detail 도구 추가는 선택(구현 시 판단).

### 검증 기준

- 단위: requireIdentifier 주입 케이스(공백·따옴표·세미콜론·백틱 거부), reconstructDdl 조립,
  MySQL 복합 인덱스 카디널리티 병합(위치별 누적→최대), PG n_distinct 음수 환산.
- 라이브: 데모 MySQL(정수·문자열 컬럼+복합 인덱스 있는 테이블)에서 SHOW CREATE TABLE 원문·카디널리티
  실측, 데모 PG에서 RECONSTRUCTED DDL+indexdef 원문·n_distinct 추정 라벨 확인. 패널 아코디언
  스크린샷(레퍼런스 이미지와 대비). 존재하지 않는 테이블명·주입 시도 문자열 → 명확한 거부.
- 문서: VERIFICATION 절 추가, CHANGELOG, 블로그 14편 섹션(레퍼런스 패리티 대비표와 함께).

## 심화 아크 4 — 레퍼런스 화면 패리티 총정리 (2026-07-15)

레퍼런스 발표의 "문제 쿼리 식별" 화면 11장을 컬럼 단위로 전수 대조한 결과. 뼈대(3탭·시점 비교·
증감/신규 감지·활용사례 3종 흐름)는 원래 동작했고, 이번 아크로 표·그래프까지 맞췄다.

### 완료 (VERIFICATION 66~68절)

| 레퍼런스 화면 | DBTower 구현 | 절 |
|---|---|---|
| 테이블 상세(CREATE TABLE·크기 통계·인덱스 카디널리티) | tableDetail 5기종, NATIVE/RECONSTRUCTED 출처 구분, PG는 pg_get_constraintdef로 FK·CHECK까지 | 66 |
| 상위 SQL 표(Call/sec·Latency·Row Examined Avg) | Top Query 기본뷰 컬럼 교체, Call/sec는 스냅샷 차분(이력 없으면 "—") | 67 |
| Slow Query 표(User@host·Lock_time·Rows_sent) | MySQL slow_log 확장, 타 기종 미확보는 "—", Captured는 (UTC) 명기 | 67 |
| MongoDB Plan(IXSCAN/COLLSCAN) | system.profile planSummary 배지(초록/빨강) | 67 |
| Monitoring 탭 CPU%·Connections 그래프 | PrometheusClient(query_range) + /metrics — node_exporter·기종 exporter, 미수집 사유 정직 표기 | 68 |
| CPU 그래프 드래그로 시점 선택 | 드래그 차트 QPS ↔ CPU% 토글 (+ 이 작업이 프론트 UTC 스큐 버그를 발굴·수정) | 68 |
| 비교뷰 Load 증감·Monitoring QPS 병치·Mongo 집계 Plan | 최종 전수 재검증에서 나온 마지막 3건 마감 | 69 |

### 남은 조각 — 착수 명세 (Opus)

> 인스턴스 메타(팀 라벨·console_url V12)와 데이터 마스킹은 **구현 완료**(VERIFICATION 70·71절). CloudWatch·소소 잔여만 남음.

| 항목 | 명세 | 검증 기준 |
|---|---|---|
| 인스턴스 메타: 담당팀/Slack 라벨 + 콘솔 딥링크 | 레퍼런스 활용사례 화면의 "Slack Group: 팀명 / AWS Link: PI" 대응. 등록 정보에 선택 필드 2개(V12): `team_label`(자유 문자열 — **Phase 3 LBAC의 team_label과 같은 컬럼으로 설계해 이중 마이그레이션 방지**), `console_url`(자유 URL — Grafana 대시보드·AWS PI·내부 위키 등 조직이 쓰는 콘솔 아무거나). 상세 패널 상단에 "담당: {team} · 콘솔 열기 ↗" 표시, 회귀/문의 웹훅에도 라벨 포함(어느 팀 채널로 갈 문제인지). AWS SDK 연동이 아니라 **URL 일반화로 PI 딥링크 패리티를 흡수**한다 — RDS 쓰는 조직은 PI URL을, 셀프호스트는 Grafana URL을 넣으면 된다 | 등록 폼에 두 필드 입력 → 상세 패널·웹훅 메시지에 라벨·링크 표시. 미입력 시 표기 생략(강제 아님) |
| CloudWatch 메트릭 소스 | RDS 등록 대상일 때만 의미. Phase 5 디스크 예측(78절 완료)에서 재평가한 결정: **Prometheus 단일 소스로 출시, CloudWatch는 후속** — AWS SDK 의존 추가는 실제 RDS 등록 수요가 생길 때. DiskForecastAdvisor는 PrometheusClient 하나만 주입받으므로 소스 추상화 시 이 한 지점만 인터페이스로 바꾸면 된다(구조 준비 완료) | RDS 인스턴스 등록 수요 발생 시 착수 |
| 소소 잔여 | (1) Slow Query 타임스탬프 브라우저 로컬(KST) 표시 옵션 — 현재는 "(UTC)" 명기로 정직 처리, parseApiTime 재사용으로 변환 가능하나 capturedAt이 ISO가 아닌 기종(Mongo 원문 문자열)이 있어 기종별 파싱 필요. (2) Mongo 장기 조회 시간대별 샘플링 — 레퍼런스는 mongod.log 파싱 기반, 우리는 system.profile(순환 컬렉션) 기반이라 보존 창이 다름을 note로 정직 표기하는 것까지 | 낮은 우선순위 — 다른 조각과 겹칠 때 처리 |
| 데이터 마스킹 (**완료 — 70절**, 배선 4곳 포함) | analysis/QueryMasker(리터럴 전용 문자 스캐너 — 문자열 '...'·숫자·$$...$$는 ?로, 식별자·따옴표 식별자·$1·주석은 보존). 배선 4곳(완료): RegressionDetector.java:110(d.queryText — 웹훅+AI 프롬프트 공통 상류), InquiryService submit 상단(req.sql 1회 마스킹 후 embed·본문·스키마 요약 공유), InsightController /ai-analysis의 req.sql (mask-ai-prompt 토글, 기본 false — AI 정확도 트레이드오프 명시), DiagnosisService:158-159(MCP arguments.sql + observation snippet). MySQL/PG 회귀 텍스트는 이미 정규화라 멱등, Oracle/Mongo·사용자 입력·LLM 작성 경로가 실수요 | 리터럴 치환 단위(이스케이프·달러 인용·16진·식별자 꼬리 숫자 보존), 문의 embed 실측에서 리터럴 가려짐, mask-ai-prompt 기본 false 확인 |

## 심화 아크 5 — MCP 채널 루프·digest 위생 (레퍼런스 2부 대조, 2026-07-15)

> **구현 완료** (VERIFICATION 70·71절): 데이터 마스킹 배선 4곳, StatsCollectionAdvisor(digest 포화·소실·PS 사각 실측 + PG dealloc), 알림 진단 딥링크(dbtower.base-url·질문 프리필), metrics MCP 도구(14종). 인스턴스 팀 라벨+console_url(V12)도 함께 완료. 잔여: Slack/Discord 봇 인바운드(후속 선택 모듈), Slow KST 표시·Mongo 샘플링(소소). 아래 명세는 기록.

레퍼런스 발표 2부(MCP 활용 4장 + Lessons Learned 5장)를 장 단위로 대조한 결과. 대조 과정에서
레퍼런스의 교훈 두 개(digest 포화·Prepared Statement 사각)가 **DBTower에도 실재함을 데모 DB로
입증**했다 — 이 아크는 화면 패리티가 아니라 수집 신뢰도의 사각을 메우는 아크다.

### 대조 결과 (9장)

| 레퍼런스 장 | DBTower 현재 | 판정 |
|---|---|---|
| MCP 정의·제공 이유(웹 외 AI 에이전트 채널) | MCP 서버(stdio/HTTP) + 자연어 진단 + 웹 콘솔 MCP 카드 | 대응 |
| MCP 제공 기능 6종(시점비교·WaitEvent·EXPLAIN·실시간지표·SchemaDiff·파티션) | 도구 13종(compare·wait_events·explain·health·activity·sessions·replication·schema_diff·partitions·query_stats·slow_queries·schema·list_instances)으로 6/6 커버 | 대응 (metrics 도구만 소소 잔여) |
| Slack에서 MCP 활용(알럿 쓰레드 이모지 → AI 분석 → 댓글) | 자연어 진단은 웹 콘솔에만 — 알림 채널에서 진단을 트리거하는 루프 없음 | **갭 → 아래 명세 1** |
| 단계별 보안·인증(요청 검증·내부망 격리·데이터 마스킹) | 요청 검증=세션·API 토큰·MCP ADMIN 게이트(완), 내부망 격리=배포 구성 영역(operations.md 가이드로), 마스킹=심화 아크 4 명세 진행 중 | 부분 대응 |
| 메모리 영향도(max_digest_length 계산식) | 운영 지식 미문서화 | 명세 2에 흡수 |
| digest 저장 건수 이슈(테이블 FULL 시 신규 쿼리 통계 소실) | **동일 사각 실재** — 같은 events_statements_summary_by_digest 소스라 포화 시 신규 쿼리 감지(회귀·NEW)가 조용히 무력화. 감시 없음. 실측: digests_size=10,000·Performance_schema_digest_lost 카운터로 감지 가능 확인 | **갭 → 명세 2** |
| 해결 방안(사이즈 변경 + 80% 자동 truncate) | DBTower는 "대상 DB를 바꾸지 않는다" 원칙 — 자동 truncate 불가 판단. 경보 + 명령 안내(PITR·gh-ost와 같은 생성·안내 모델)까지 | 명세 2 (원칙 판단 포함) |
| PostgreSQL digest(파스트리 기반·evict 자동) | PG는 포화 소실은 없으나 evict가 잦으면 시점 비교 신뢰도가 떨어짐 — pg_stat_statements_info.dealloc 실측 확인(감지 가능) | 명세 2 |
| Prepared Statement 이슈(PI 통계 미표시·사용 자제 가이드) | **동일 사각 실측 입증**: PREPARE/EXECUTE 실행 시 digest에 `EXECUTE s1 USING @?`만 집계되고 내부 쿼리는 미집계 — PS 워크로드는 Top Query·회귀 감지에 익명 부하로만 보임. 감지·안내 없음 | **갭 → 명세 3** |

### 착수 명세 (Opus)

| 항목 | 명세 | 검증 기준 |
|---|---|---|
| 1. 알림 → 진단 루프 | 레퍼런스의 "알럿 쓰레드 이모지 → AI 분석 댓글"을 셀프호스트 제약(봇 등록·이벤트 수신 인프라 없음)에 맞게 두 단계로. **1단계(지금)**: 회귀·이상·운영 경보 웹훅에 콘솔 진단 딥링크 포함 — 신규 설정 `dbtower.base-url`(빈 값이면 링크 생략), URL은 `{base}/?instance={id}&diagnose={질문 프리필}` 형태로 자연어 진단 입력을 미리 채움. 클릭 한 번으로 "이 알림 원인 분석해줘"가 실행되는 흐름. **2단계(후속·선택 모듈)**: Slack Events API/Discord 봇 인바운드(이모지·멘션 수신 → DiagnosisService 호출 → 스레드 댓글). 외부 앱 등록·토큰·공개 엔드포인트가 필요해 셀프호스트 기본에서 제외하고 별도 모듈로 — 보안 3단계(채널·유저 화이트리스트, 마스킹 필수)를 함께 설계 | 1단계: 웹훅 실발사에서 딥링크 클릭 → 콘솔이 해당 인스턴스+질문 프리필로 열림. base-url 미설정 시 링크 없음 |
| 2. digest 위생 Advisor (신규) | advisor에 수집 신뢰도 점검 추가. **MySQL**: (a) digest 포화율 = COUNT(events_statements_summary_by_digest)/@@performance_schema_digests_size ≥ 80% WARNING(레퍼런스의 80% 기준 차용, 단 truncate 대신 경보), (b) `Performance_schema_digest_lost` > 0 CRITICAL — "신규 쿼리 통계가 이미 소실되고 있음: 신규 쿼리 감지·회귀 감지가 부분 무력화" 명시, (c) @@max_digest_length ≤ 1024 WARNING — 앞부분이 같은 긴 쿼리들이 동일 digest로 합쳐져 Top Query가 뭉개지는 위험(메모리 영향 계산식 — digest_length×동시세션, history 3테이블 — 을 근거 문서에 수록). 조치는 안내만: TRUNCATE 명령·파라미터 변경 문안 생성(대상 변경 금지 원칙, PITR와 같은 모델). **PostgreSQL**: pg_stat_statements_info.dealloc을 스냅샷 차분으로 감시 — 증가 중이면 "통계 evict 진행 중: 저빈도 쿼리의 시점 비교 신뢰도 저하" INFO/WARNING, pg_stat_statements.max 대비 사용률 병기 | 데모 MySQL에서 digests_size를 작게 만들 수 없으므로(재기동 파라미터) lost=0·포화율 정상 케이스 + 임계 로직 단위 테스트. PG는 dealloc 차분 단위 + 라이브 수치 확인 |
| 3. Prepared Statement 사각 감지 | 실측 근거: `PREPARE s1 FROM 'SELECT ... WHERE id > ?'` 후 EXECUTE 2회 → digest에는 `EXECUTE s1 USING @?`(COUNT 2)만 남고 내부 SELECT는 미집계(2026-07-15 데모 실측). 명세: (a) Advisor — performance_schema.prepared_statements_instances 행수·COUNT_EXECUTE 합이 전체 실행 대비 유의하면 "Top Query에 보이지 않는 PS 부하 존재" WARNING + prepared_statements_instances의 SQL_TEXT 상위를 근거로 첨부(이 테이블에는 원문이 있음 — digest 사각의 보완 소스), (b) 문서 — PS는 세션 로컬(MySQL/PG)이라 커넥션 풀에서 중복 캐싱되는 메모리 특성과 "짧은 커넥션에서 매번 prepare→execute→close는 역효과" 가이드(레퍼런스 교훈 수록), (c) binary protocol(JDBC useServerPrepStmts) 경로 실측 추가 | PS 워크로드 생성 → Advisor가 사각 경고 + prepared_statements_instances 원문 노출 실측. 단위: 임계 판정 |
| 4. 소소: metrics MCP 도구 | GET /metrics(CPU·Connections)를 MCP 도구 `metrics`로 노출 — 레퍼런스 "모니터링 지표(Realtime Metrics)" 카드의 완전 대응. AI 진단이 "그 시각 CPU가 실제로 높았나"를 스스로 확인할 수 있게 됨(자연어 진단의 증거 수집 도구 확장) | tools/list에 metrics 노출, 자연어 진단이 CPU 질문에 이 도구를 호출하는 것 확인 |

## 프로덕션·셀프호스트 준비도 감사 (2026-07-14, 미착수 갭)

> 병렬 감사 3축(온보딩·운영보안·라이선스/배포) 종합. "다른 사람/조직이 셀프호스트로 실제 운영할 수
> 있는가"의 관문. 코드 실확인 + 웹서칭 근거. 각 항목은 착수 전 재검증 대상.
>
> **읽는 법 (중복 방지)**: 이 절과 아래 "대규모 스케일" 절은 *분석 근거*다. 실행 계획·최신 상태의
> **단일 출처(SoT)는 아래 "프로덕션 로드맵 (Phase 0~5)" 절**이다 — P0=Phase 0, P1≈Phase 1,
> 수천 대 스케일=Phase 4로 이어진다. 상태가 어긋나면 코드 실확인 기준인 Phase 0~5 표가 최신이다.

### 이미 탄탄한 것 (오해 방지)

- 공개 GHCR 멀티아치 이미지 익명 pull — `docker compose -f docker-compose.app.yml up -d` 한 방(JDK/Gradle 불요)
- admin 부트스트랩 안전 — 첫 부팅에만 생성, 미설정 시 랜덤 비번 로그 1회, `admin/admin` 명시적 거부(BCrypt)
- 커밋된 실비밀 없음(`.env` 미추적, `.env.example`만 placeholder)
- Flyway fail-fast 업그레이드 / 일관된 SemVer·CHANGELOG·릴리스 파이프라인(태그 v1.0.0~1.1.0)

### P0 — 배포 전 필수 (이게 없으면 "남이 못 쓴다")

| # | 갭 | 근거 / 위치 | 왜 블로커 |
|---|---|---|---|
| P0-1 | **LICENSE 파일 부재** | 루트·소스헤더·build.gradle·README 어디에도 없음 | 저작권 기본값 = All Rights Reserved. 공개 저장소·GHCR 이미지가 있어도 사용 허가가 아님 — 복사·수정·재배포·셀프호스트가 법적 금지. Apache-2.0/MIT + 번들 드라이버(MySQL GPLv2·Oracle 독점) `NOTICE` 동반 |
| P0-2 | **암호화 fail-open(대상 비번 평문 저장)** | `SecretCipher.java:55,87` prod 프로필만 fail-closed. 셀프호스트는 `docker` 프로필로 뜨고 `prod`는 저장소에 없음. `.env.example:13` 키 공란 | 키 없이 띄우면 WARN 뒤 정상 부팅 → 대상 DB 접속 비번을 메타 DB에 평문 저장(CWE-312). 고침: `docker` fail-closed 또는 compose에서 `${DBTOWER_ENCRYPTION_KEY:?}` 필수화 |
| P0-3 | **커밋된 바이너리 DB `data/dbhub.mv.db`** | `data/`가 `.gitignore` 미포함·추적됨(2.3MB). USERS(비번) 테이블 든 옛 H2, 앱 미사용(고아) | 공개 저장소에 나갈 물건 아님 — 삭제 + `data/` gitignore |

### P1 — 실사용(여러 사람이 진지하게 같이 쓸 때) 부족

| # | 갭 | 근거 / 위치 | 영향 |
|---|---|---|---|
| P1-1 | TLS/HTTPS 전무 | 평문 HTTP 8080만. 리버스 프록시·`forward-headers-strategy` 가이드 없음 | 로그인 비번·세션쿠키·Bearer 토큰 평문 전송 |
| P1-2 | 플랫폼 메타 DB 백업 없음 | 백업은 대상 DB만. 자기 자신(dbtower DB)은 볼륨 `dbtower-meta-data` 하나 의존 | 볼륨 소실 = 사용자·모든 자격증명·정책·이력 전소 |
| P1-3 | README 로그인 절 ↔ 기본값 모순 | `.env.example:8` admin 비번 공란인데 `README.md:242`는 그 값으로 로그인하라 함 | 그대로 따르면 랜덤 비번이 로그에만 있고 안내 없어 첫 벽에서 막힘 |
| P1-4 | AI 기능 2종 배포 이미지에서 죽음 | 규칙 파일 `docs/ai-analysis-rules.md`가 `.dockerignore:8`로 제외(빈 프롬프트) + `ANTHROPIC_API_KEY` compose 미배선·claude CLI 미번들 | README가 내세운 AI 분석이 셀프호스트에선 항상 OFF |
| P1-5 | `/actuator/prometheus` 무인증 노출 | `SecurityConfig.java:64` permitAll("네트워크 제한 전제")인데 8080이 호스트 노출·그 가이드 문서에 없음 | 도달 가능한 누구나 메트릭 스크랩 |

### P2 — 마찰·커뮤니티 위생

- `CONTRIBUTING.md`·`CODE_OF_CONDUCT.md`·이슈/PR 템플릿 전무, README에 기여·이슈 안내 없음
- 시스템 요구사항 절 없음(JDK 21·RAM/CPU·지원 DB 버전)
- `gh-ost`·`claude` CLI 호출하는데 번들·설치안내 없음
- `DBTOWER_API_TOKEN` 미설정 시 재시작마다 토큰 재생성 → MCP 자동화 깨짐
- 세션 인메모리(공유 스토어 없음) → 사실상 단일 노드. 로그인 rate-limit/잠금 없음
- 역할 VIEWER/ADMIN 2개뿐·per-instance 스코핑 없음(→ 대규모 멀티테넌시와 연결)
- 자잘: `HELP.md` Spring 보일러플레이트 / `open` macOS 전용 / 데모 compose 약한 하드코딩 비번 노출 / 미사용 Lombok 의존성이 AGENTS.md와 모순

## 대규모 스케일 — 수천 대 DB (넷플릭스·현대오토에버급, 분석)

> 한 조직이 셀프호스트 한 대로 수천 대를 관제하는 시나리오. 코드 실확인 결과 테마 F의 "직렬 루프"
> 서술은 낡음 — 수집 병렬화·스케줄러 풀 분리는 이미 코드 반영됨. 아래는 갱신된 분석.

### 이미 된 것

- 수집 병렬 — `SnapshotScheduler`가 워커 풀(`dbtower.snapshot.workers`, 기본 4)로 인스턴스별 병렬 + 지터
- 스케줄러 풀 분리 — `SchedulingConfig`의 `ThreadPoolTaskScheduler`(pool-size 기본 4)
- 인스턴스 수집 격리 토글(`collectionEnabled`) + 죽은 대상 지수 백오프 + ShedLock 리더 선출(28절)
- → 수백 대 규모까진 워커 튜닝으로 지금도 버팀

### 수천 대에서 깨지는 두 개의 벽

- **벽 1 — 수집이 설계상 단일 노드.** `collect()`의 `@SchedulerLock`이 "한 시점에 한 노드만 전체 수집"을
  강제. HA 페일오버엔 완벽하나, 뒤집으면 노드를 늘려도 수집 처리량이 안 늚(수직 확장뿐). 한 사이클이
  `lockAtMostFor=PT2M` 초과 시 구조가 깨짐
- **벽 2 — 인스턴스별 상시 커넥션 풀.** `ConnectionPools`가 DB 하나당 HikariCP 풀(`minimumIdle=1`) 유지
  → 수천 대 = 수천 풀 + 수천 상시 커넥션(FD·메모리). 수직 확장의 천장
- 부차: 메타 DB 인입/저장 폭증(단일 PG, 파티셔닝·시계열화 미대응) / 알림 폭주 / UI 집계 지연 / 팀 격리 부재

### 규모 감각 (대략 추정 — 실측 아님)

| 규모 | 지금 아키텍처 | 필요한 것 |
|---|---|---|
| ~수백 대 | 편안함(워커 튜닝) | 없음 |
| ~1~2천 대 | 빡센 수직 튜닝으로 가능, 커넥션 풀이 천장 | 메타DB 튜닝·알림 레이트리밋 |
| 수천~수만 대 | 구조적으로 안 됨 | 수집 샤딩 or 에이전트 push + 메타DB 파티셔닝 + 멀티테넌시 |

### "분산"의 두 종류 (핵심 구분)

- **분산 ① 안 죽으려고(HA):** 여러 대 띄워 한 대 죽어도 나머지가 받음 — **이미 됨**(ShedLock). 처리량은 안 늚
- **분산 ② 더 많이 처리하려고(샤딩):** 수천 대를 collector 여러 대가 나눠 담당 — **아직 안 됨.** 수천 대엔 필수
- **함정:** ①용 ShedLock(한 대만 수집)이 오히려 ②를 막는다. 수천 대로 가려면 단일 락을 샤드별 락으로 교체 필요
- 요약: 한 대짜리 셀프호스트는 수천 대에서 한 노드 용량 한계에 부딪힌다 → 수집을 여러 노드에 나눠 담당(샤딩)해야 한다. self-host의 결함이 아니라 한 노드의 한계

### 대규모 도약 순서

1. 수집 병렬화·스케줄러 풀 분리 — **완료**(코드 반영)
2. 알림 글로벌 레이트리밋 + 폭주 묶음 / 헬스 스코어 캐시 — **완료**(코드 반영 — Phase 4 표 참조)
3. 완전 HA — 공유 세션 스토어(Redis 등) + 메타 DB 복제
4. **수집 샤딩**(consistent hashing, ShedLock 단일 락 → 샤드별 락) **또는 에이전트 push 모델** — 현재 설계만·범위 밖
5. 메타 DB 파티셔닝/시계열화
6. **팀별 멀티테넌시 + per-instance 권한**(role explosion 주의, 라벨/속성 기반)
7. 커넥션 온디맨드화(상시 풀 → 필요 시 연결)

### "다 쓰게 하고싶다" 두 갈래

1. **남들이 각자 셀프호스트**(오픈소스로 풀기) → 선결은 위 P0(라이선스·평문암호화·커밋된 DB파일)
2. **한 조직이 수천 대를 한 곳에서**(현대오토에버·넷플릭스급) → 위 스케일·HA + 멀티테넌시

## 프로덕션 로드맵 (Phase 0~5) — 셀프호스트 제품화 실행 순서

> 목표 전환(2026-07-15): DBTower를 실제 프로덕션급 셀프호스트 제품으로 계속 발전시킨다. 위 "준비도
> 감사"·"대규모 스케일" 분석을 실행 순서로 배열했다. 상태는 코드 실확인(2026-07-15, 설정·보안·의존성·
> 마이그레이션 파일 전문 대조) 기준. 순서 원칙: 아무도 못 씀 → 한 명 → 한 팀 → 여러 팀 → 수천 대.
> 뒤 단계를 앞 단계보다 먼저 하지 않는다.
>
> 혼동 주의(반복된 착각의 근원): DBTower는 **대상 DB를 향한(target-facing) 기능**은 갖췄으나
> **플랫폼 자신·사용자를 향한(platform-facing) 기능**이 비어 있다. 이름이 비슷해 "이미 됐겠지" 착각을
> 부른다 — 대상 접속 TLS(`useTls`, V6 마이그레이션)는 있으나 웹 HTTPS는 없고, 대상 백업 원격 S3
> 보관(V7)은 있으나 메타DB 자기백업은 없고, 로그인 실패 감사(AuthenticationAuditListener)는 있으나
> 로그인 rate-limit은 없다. "부분" 표기는 구조/일부 기종만 되고 기본 셀프호스트 경로엔 안 되는 상태.

> **구현 담당: Opus. 아래 각 Phase는 착수 명세다.** Phase D·deepening-spec과 같은 원칙 —
> 각 항목에 (1) 현재 상태와 코드 포인터, (2) 구현 방법(클래스·설정·마이그레이션 수준), (3) 함정,
> (4) 검증 기준을 명시한다. 공통 불변식: 대상 DB 읽기 전용(대상 설정 변경 금지 — "켜져 있으면 쓴다"
> 게이트), 비밀값 argv 금지(환경변수), 미지원 정직 표기(UNSUPPORTED 위장 금지), 성능 주장은 실측,
> 모든 완료는 docs/VERIFICATION.md에 절 추가, 모듈 규칙은 AGENTS.md "모듈 내부 패키지 규칙" 준수.

### Phase 0 — 배포 블로커 (완료: VERIFICATION 63절)

| 항목 | 상태 | 비고 |
|---|---|---|
| LICENSE + NOTICE | **완료(63절)** | Apache-2.0 전문 + 번들 드라이버·CLI 재배포 고지 |
| 암호화 fail-closed 확대 | **완료(63절)** | `SecretCipher` 판정 집합 {prod, docker} — 셀프호스트(docker) 경로 평문 저장 차단. compose `${...:?}` 이중 방어. blank/dev/test는 유지(테스트 컨텍스트 부팅). 마이그레이션 노트는 CHANGELOG |
| data/dbhub.mv.db 제거 | **완료(63절)** | git rm + `.gitignore` data/. 히스토리 세척은 공개 전 사용자 결정(파괴적 — 미실행) |
| AI 기능 배선 | **완료(63절)** | `.dockerignore` 예외 + `Dockerfile COPY` + compose ANTHROPIC_API_KEY. README 로그인 정합 완료 |

### Phase 1 — 단일노드 프로덕션 하드닝 (완료 5/5, VERIFICATION 64절)

| 항목 | 상태 | 비고 |
|---|---|---|
| TLS(리버스 프록시) | **완료(64절)** | application-docker.yml `forward-headers-strategy: framework` + 세션·CSRF 쿠키 Secure 토글(`DBTOWER_COOKIE_SECURE`). 인증서는 프록시 종단 |
| 메타DB 자기백업 | **완료(64절)** | `MetaBackupJob`(@Scheduled+@SchedulerLock) pg_dump 자기 덤프 + 원격 meta/ 네임스페이스. PGPASSWORD env, PG 아니면 스킵. 순수 로직(URL 파싱·보존) 단위 테스트 |
| 로그인 rate-limit | **완료(64절 → 80절에서 분산화)** | `LoginAttemptGuard`+`LoginLockFilter` — 계정별 잠금, login.html 남은시간 표시. 라이브 11회째 차단 실측. 인메모리 노드별 한계는 V17 메타 DB 이관으로 해소(80절 — 노드를 오간 실패도 하나의 임계) |
| 로깅·커뮤니티 파일 | **완료(64절)** | 스프링 부트 네이티브 롤링 파일(janino 회피), CONTRIBUTING·CoC·이슈/PR 템플릿, README 시스템 요구사항 |
| actuator·API 토큰 안정화 | **완료(64절)** | `MetricsTokenFilter` — `dbtower.metrics.token` 설정 시 /actuator/prometheus에 Bearer/?token 검사(미설정 시 현행+WARN), 라이브 401/200. API 토큰은 `SettingStore`+V11 `platform_setting`으로 영속(미설정 시 생성·저장) — 라이브 재시작 후 토큰 동일 확인 |

### Phase 2 — 기능 심화 (제품 깊이)

| 항목 | 상태 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|
| 로그 백업 5기종 확장 | **완료(72~74절 — 5/5 SUCCESS + 체인 보충 수집)** | 현재 MSSQL만 실동작(`MsSqlOperator.java:60` BACKUP LOG). 확장 축: **MySQL** — `FLUSH BINARY LOGS` 후 직전 binlog 파일 수집(binlog 활성은 대상 설정이므로 "켜져 있으면 쓴다" 게이트: `SHOW VARIABLES LIKE 'log_bin'` OFF면 UNSUPPORTED 사유 명시). **PG** — WAL 아카이빙은 archive_command 등 대상 서버 설정 필요 → 대상 설정 변경 금지 원칙상 자동화하지 않고, `pg_basebackup` 기반 물리 FULL(+`--wal-method=stream`)을 별도 타입으로 검토(이것도 replication 권한 게이트). **Oracle** — RMAN 없이는 정직 UNSUPPORTED 유지. **Mongo** — `--oplog` 옵션 백업(복제셋 게이트). 각 기종 지원/미지원과 사유를 BackupResult에 명시 | 게이트 ON 환경(docker/ 시드에 binlog 활성) 실측 + OFF 환경 UNSUPPORTED 사유 확인 |
| PITR(임의 시점 복구) | **완료(72절, 권장 범위 1·2 + MSSQL e2e)** | **범위 결정이 선행**: 완전한 PITR 오케스트레이션은 대상 서버 구성(아카이브 경로·restore_command)을 요구해 "대상을 바꾸지 않는다" 정체성과 충돌. 권장 범위 — (1) 로그체인 보유 시 "복원 가능 시점 범위" 표시(FULL 시각 ~ 마지막 LOG 시각), (2) 기종별 복원 명령 **생성·안내**(실행은 사람: MSSQL RESTORE ... STOPAT, MySQL mysqlbinlog --stop-datetime 파이프), (3) 복원 검증(A7)을 STOPAT 경로로 확장(MSSQL은 임시 DB에 실제 시점 복원 검증 가능). 자동 실행까지 갈지는 이 범위의 실사용 후 재결정 | MSSQL에서 FULL+LOG 체인 → 중간 시점 복원 검증 e2e(임시 DB). 안내 명령 문자열 단위 테스트 |
| 문의 테이블 구조 첨부 | **완료(65절)** | ReferencedTables 추출기 + ReferencedSchemaService(교집합·인덱스 중심 요약·notFound) + 문의 embed/본문 + 사이트 패널. 조인 테이블 포함, 데모 PG 라이브 |
| 데이터 마스킹 | **완료(70절)** | `analysis/QueryMasker`(순수 유틸) — SQL **리터럴만** 치환(문자열 '...'→'?', 숫자→?, 식별자·구조 보존 — 진단력 유지가 목적이므로 과도 마스킹 금지). 적용 지점은 외부 발신 직전 3곳: WebhookNotifier(회귀·문의), AiAnalyzer 프롬프트(선택 토글), MCP 응답(DiagnosisService). 설정 `dbtower.masking.enabled`(기본 true)·`mask-ai-prompt`(기본 false — AI 판정 정확도와 트레이드오프 명시). 레퍼런스 MCP 보안 3단계의 3(데이터 마스킹) 대응 | 리터럴 치환 단위 테스트(인젝션형 문자열·이스케이프 포함), 문의 embed 실측에서 리터럴 가려짐 확인 |
| 백업 잔여(정석 완성도) | (1) **PG 물리 FULL — 완료(75절)**: PHYSICAL 타입(pg_basebackup -Ft stdout) + 실제 시점 복원 e2e(마커 A/B — restore_command 함정 실측·문안 반영). (2) **Oracle RMAN — 경로 구현 완료(75절)**: oracle-rman-command 지정 시 NOT BACKED UP 1 TIMES/BACKUP DATABASE, 데모 이미지 rman 부재 실측으로 파일 수집 폴백. (3) 잔여: Mongo oplog ts 증분(겹침 검증), pg_receivewal 스트리밍 모듈(무결 연속 보장) | Mongo: 겹침 엔트리 검증. pg_receivewal: 프로세스 생존·재시작 e2e |

### Phase 3 — 멀티유저 / 멀티테넌시 (여러 팀이 한 곳에)

| 항목 | 상태 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|
| per-instance 접근 스코핑 | **완료(77절)** | 라벨 기반(LBAC, PMM 선례) — role explosion(팀마다 롤 양산) 회피. V11: `database_instance.team_label`(nullable=전역), `platform_user.team_label`(+ ADMIN은 전역 관리 유지, 팀 스코프 롤 신설 여부는 구현 시 결정). 강제 지점은 **한 곳**: `RegistryService.findById/findAll`에 현재 인증 주체의 스코프 필터(모든 모듈이 registry를 경유하므로 단일 경계 — 컨트롤러마다 뿌리지 않는다). MCP·Bearer 토큰에도 동일 적용(토큰-팀 매핑). **함정**: 스코프 밖 인스턴스는 403이 아니라 404(존재 노출 방지) | 팀A 유저가 팀B 인스턴스 조회 404, 전역 ADMIN 전체 조회, MCP 동일 경계 테스트 |
| 공유 세션 + 완전 HA | **완료(77·80절)** — 공유 세션·재시작 생존(77) + 2노드에서 한 노드 kill 후 같은 쿠키 무중단(80) + 로그인 잠금 카운터 메타 DB 이관(V17 login_attempt — 세션 스토어 대신 메타 DB 테이블: 실패 카운터는 세션과 수명이 달라 세션에 실으면 쿠키를 버리는 것으로 우회된다) | 세션: **spring-session-jdbc**(메타DB 재사용 — ShedLock과 같은 "새 인프라 없이" 논리, Redis는 규모 커지면 승급). 메타DB 복제는 앱 범위 밖 — operations.md에 소유 계층(CloudNativePG·RDS Multi-AZ) 가이드로 | 2노드에서 한 노드 kill 후 같은 세션 쿠키로 무중단 사용 + 노드 분산 실패 3회가 하나의 임계로 잠김 — **충족(80절 실측)** |

### Phase 4 — 스케일 잔여 (수천 대)

| 항목 | 상태 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|
| 수집 병렬·스케줄러 풀·격리 토글·알림 레이트리밋·헬스 캐시 | **구현** | (완료 — SnapshotScheduler 워커 풀 / SchedulingConfig / collectionEnabled / WebhookNotifier rate-per-minute / ScoreService 캐시) | — |
| 노드 간 수집 샤딩 | **완료(80절)** | `dbtower.snapshot.shards`(기본 1 — 락 이름까지 현행 동일, 롤링 배포 배타 유지) — 틱마다 샤드별 `snapshot-collect-s` 락을 LockProvider로 각각 시도, 획득한 샤드(`instanceId % N`)만 수집. 노드 여럿=락 경쟁 자연 분산, 한 노드=전 샤드 인수(페일오버 무설정 유지). 샤드 수 변경은 전 노드 동시 적용 전제(주석·yml 명시). 잔여 주의: 서버 전역 지표 수집 경로 dedup은 샤드가 인스턴스 단위라 같은 서버의 두 DB가 다른 샤드에 갈 수 있어 노드 간 조정이 필요 — 실수요(수천 대) 전까지 보류 | 2노드×shards=4 분산 로그 + A kill 후 B 전 샤드 인수 — **충족(80절 실측)** |
| 메타DB 파티셔닝 | **완료(81절 — query_snapshot)** | V18 신테이블→복사→스왑(단일 트랜잭션, PG 16 identity 미지원이라 시퀀스 DEFAULT, PK에 파티션 키 포함). 보존 잡이 파티션 수명주기 관리자: 이번 달·다음 달 선생성(멱등) + 월 전체 경과 파티션 DROP + 걸친 파티션 내부만 DELETE(프루닝) + 비파티션(H2·전환 전) DELETE 폴백. DEFAULT 파티션 안전망. pg_partman 의존 없음. health_sample은 보류(정직 — 볼륨 1/100·전용 보존 잡 부재, 실측이 정당화할 때 같은 패턴) | **충족(81절)**: DELETE 200만 행 1,879.9ms → DROP 12.8ms(147배·블로트 0), EXPLAIN 프루닝(7월 조회가 6월 파티션 무접촉), 스왑 부산물로 블로트 404MB→55MB |
| 호스트 그룹핑(서버 공유 인지) | **완료(79절, 경보·스윕·UI)** | 구현: `DatabaseInstance.serverKey()`(host 소문자:port 계산 키 — 엔티티 추가·DNS 해석 없음) 파생 그룹. **dedup은 탐침·경보에만, 위험 귀속(온디맨드 점검·헬스 스코어)에는 안 한다** — 같은 서버의 두 DB가 모두 위험한 건 사실. (1) OpsAlertDetector: 서버 전역 4종(유휴 트랜잭션·복제 지연/슬롯·데드락)은 그룹 대표(id 최소)만 탐침, 공유 서버면 경보에 "(서버 x 공유 — ... 전체에 해당)" 명시, 인스턴스 스코프(수집 정지·백업 신선도)는 각자 유지. (2) AdvisorSweepJob: 호스트 스코프 Advisor(`Advisor.hostScoped()`, 디스크 예측)는 호스트+nodeFilter 그룹당 1회, 나머지는 SHARED 상태로 정직 표기. (3) 콘솔 카드에 "서버 공유 ×N" 배지. **잔여**: 서버 전역 지표의 수집 경로(wait event·parameters 스냅샷) dedup은 수집 샤딩과 함께(수집은 경보와 달리 중복이 낭비일 뿐 오보가 아니라 우선순위 낮음) | 같은 서버 2 DB에 실 유휴 트랜잭션 → 경보 1건+공유 명시, 인스턴스 스코프는 각자 — **충족(79절)**. 스윕 로그 호스트공유생략=5 실측 |
| 커넥션 온디맨드 | **완료(82절)** | minimumIdle=0 + idleTimeout·maxLifetime 명시 + 하한 가드(수집 주기+30s 미만 강제 상향 — 틱마다 재연결 방지) + 장기 미사용 풀 LRU close(evict-after-minutes, 기본 30분 — 다음 사용 시 재생성). 덤으로 잡은 구멍: SLO 헬스 폴러가 격리(collectionEnabled=false)를 무시하고 60초마다 핑 — 격리 스킵 추가(격리 기간 가용성 이력은 정직한 공백) | **충족(82절 실측)**: 격리 대상 커넥션 before 1개 영구(+SLO 핑이 Sleep 리셋) / after 0 수렴 + "유휴 풀 정리" 로그, 격리 해제 시 풀 재생성·수집 재개 정상 |

### Phase 5 — 분석 루프 (lakehouse 연동)

| 항목 | 상태 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|
| forward: offload 확대 | 미구현 (lakehouse 저장소 실분석 완료 — 명세 구체화) | 실분석(2026-07-16) 결과 현재 추출은 `query_snapshot` **하나뿐**이고 "코드 변경 0" 전제는 backup_run·plan_snapshot에만 참: **(선결 1) wait event는 메타DB에 영속 테이블이 없다** — DBTower가 `wait_event_snapshot(instance_id, captured_at, event_name, category, value)` 주기 영속을 먼저 낳아야 함(lakehouse ROADMAP 13단계 size_snapshot과 동일 패턴). **(선결 2) plan_snapshot 보존이 카운트 기반(쿼리당 최신 20, 1시간 스윕)** — "어제 하루창 추출"과 어긋나 하루가 닫히기 전 행이 지워질 수 있음 → 시간 기반 보존 병행 또는 추출 주기 단축의 정합 문서화. **(선결 3) backup_run은 사후 변이 테이블**(verify/remote가 나중에 UPDATE) — "닫힌 dt 불변" 전제 깨짐, D+1 스냅샷임을 계약 명시 필요 + 워터마크 컬럼이 started_at. lakehouse 쪽 작업은 SOURCE_TABLE 단수 상수의 테이블 스펙 레지스트리화, 게이트 4축 테이블별 프로필(completeness·freshness를 그대로 재사용하면 backup_run의 정상 상태가 fail-closed 오탐), CONTRACT §1 개정, GRANT 목록 추가 등 10개 파일 동심원 — lakehouse 저장소 이슈로 관리 | lakehouse 쪽 DAG e2e(테이블별 게이트 프로필 통과) + plan_snapshot 보존 정합 실측 |
| reverse ETL: 장기 베이스라인 | 미구현 (lakehouse 저장소 실분석 완료 — 명세 구체화) | 방향 유지: lakehouse가 메타DB에 `baseline_longterm`(instance_id, query_id, dow, hour, mean, stddev, observations, computed_at) **되쓰기**, DBTower는 Flyway로 테이블 정의 + `BaselineService` 가중 병합(관측 충분 시 결합, 없으면 현행 — 미존재/빈 테이블이면 회귀 0). 실분석으로 확정된 제약: **(1) lakehouse의 원천 접속은 계약·코드(세션 readonly) 양쪽에서 읽기전용 봉인** — 되쓰기는 별도 역할(`lakehouse_writer`, 해당 테이블만 INSERT/DELETE)·별도 WritebackConfig로만(SourceConfig 재사용 금지), CONTRACT에 되쓰기 절 신설. (2) **일간 마트(fct_query_daily)로는 dow×hour 통계 불가** — staging에서 시간대별 델타 `fct_query_hourly`(증분+워터마크 리터럴 패턴 복제) 신설이 선행. (3) 볼륨 가드: instance×query×168버킷이라 min_observations 필터(BaselineService 8관측 게이트와 정렬)+top-K로 눌러야 함. (4) 스케줄은 publish와 heartbeat 사이 writeback 태스크(단일 트랜잭션 DELETE+INSERT, publish의 원자성·행수 대조 불변식 이식) — 베이스라인 변화가 느리므로 @weekly 별도 DAG도 유효(deadman 감시 편입 조건) | 장기 테이블 주입 후 이상 판정이 계절성(예: 월요일 피크)을 오탐하지 않는 것 실측, 미존재 시 현행 동작 회귀 없음 |
| 용량 예측(디스크 포화 ETA) | **완료(78절)** | 구현: (a) node_exporter — 정석화 완료(`/:/host:ro` + `--path.rootfs=/host`, 기존 구성은 rootfs 마운트가 없어 컨테이너 자신만 보였음), (b) `insight.PrometheusClient`(루트 공개 API로 승격) + `queryScalar` 즉시값 조회, (c) 인스턴스-노드 매핑 `node_filter` **V16**(라벨 셀렉터, label="value" 형식만 허용해 PromQL 주입 방지 — nodeFilter가 mountpoint를 직접 지정하면 기본 "/" 양보: 데이터 전용 마운트가 실무 정석), (d) `advisor/internal/DiskForecastAdvisor` — 선형 ETA avail/(-deriv(avail[6h])), ETA≤3일 CRITICAL·≤14일 WARNING·추세 없어도 여유<10% WARNING, Prometheus 미설정·미수집 시 조용히 스킵(기능 게이트), 일일 스윕(웹훅)은 AdvisorService 경유 기존 경로 재사용. **실측**: 실쓰기 ~17MB/s → CRITICAL "약 0.7일 내 (여유 76.8%)" — 여유가 넉넉해도 속도가 빠르면 치명(잔량 경보만으론 침묵하는 케이스). 단위 9건, 총 419건 그린. **잔여**: 같은 nodeFilter 공유 인스턴스 간 호스트당 경보 dedup(Phase 4 호스트 그룹핑과 함께), RDS CloudWatch `FreeStorageSpace` 소스 추상화(후속). 스케일 실행은 여전히 범위 밖 — 프로비저닝 계층 소유, DBTower는 신호까지 | 데모 스택에서 대량 적재로 여유 감소 → 예측 ETA 산출·경보 실측 — **충족(78절)**. Prometheus 미설정 시 스킵은 단위로 고정 |

## 범위 밖 (여전히 의도적으로 안 한다)

- **SQL 변경 심의·승인 워크플로 (Bytebase/Archery형)** — gh-ost류가 MySQL 전용이라 이기종 정체성과
  충돌하고, 쓰기 경로 거버넌스는 읽기·진단 DNA와 다른 카테고리. 인상적이지만 초점을 흐린다.
- **자동 인덱스 생성, 파티션 자동 관리** — Phase D는 "조회·조언"까지만(pganalyze의 "AI-assisted but
  developer-driven" 원칙). 대상 DB를 스스로 바꾸는 순간 다른 제품이 된다.
- **엔진 패치/업그레이드 오케스트레이션** — 플랫폼별 도구(K8s Operator 롤링 업데이트, RDS 유지관리
  창)의 영역. DBTower의 역할은 패치 전후 검증(파라미터 드리프트·Schema Diff·시점 비교)까지.
- **자동 페일오버** — HA 토폴로지의 소유자(Operator/managed 서비스)가 할 일. 관제 도구가 페일오버를
  트리거하면 소유자와 판단이 갈릴 때 스플릿 브레인 위험. 감지·알림·원인 진단까지가 DBTower의 몫.
- DBaaS 수준의 멀티테넌시·과금·셀프서비스 포털(IDP) — 플랫폼 엔지니어링의 다른 제품 영역.
- Wait Event·Schema Diff·DB 생성 자동화는 범위 밖에서 로드맵(B·C)으로 승격했고, 이상 감지·Advisors·
  자연어 진단·파티션 조회는 Phase D로 승격했다. "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.

## 심화 후보 백로그 — 5기종 전수 갭 조사 (2026-07-07, 공식 문서·상용 도구 대조)

> **착수 명세는 [docs/deepening-spec.md](deepening-spec.md)** — 아크 1~4차 구현 방법(쿼리·클래스
> 수준)·함정·검증 기준·산출물 체크리스트. 아크 1~4차는 그 명세대로 구현 완료(VERIFICATION 57~60절).

기종별 담당 조사에서 검증된 후보만 수록(URL은 조사 시점 실확인). 정체성 필터 통과분만 —
대상 설정 변경이 필요한 것(track_io_timing 활성화, Query Store ON, setParameter,
프로파일러 레벨, blocked process threshold 등)은 전부 "켜져 있으면 쓴다" 게이트 또는 제외.

### 테마 A — 플랜 플립 5기종 완성 (현재 PG만 완전, 최우선 추천)
| 기종 | 방법 | 비고 |
|---|---|---|
| MySQL | performance_schema digest의 QUERY_SAMPLE_TEXT(리터럴 샘플, 기본 60초 신선도)를 EXPLAIN에 투입 | Datadog DBM 동일 방식. 1024B 절단 시 폴백. "샘플 기반" 표기 |
| SQL Server | Query Store sys.query_store_plan — query_id당 plan_id/query_plan_hash 이력 보존(캐시 축출 무관) | NATIVE. is_forced_plan·force_failure_reason까지 관측. QS 게이트(2022 신규 DB 기본 ON) |
| Oracle | v$sqlstats가 sql_id×plan_hash_value당 1행 — 폴링에 phv 컬럼 추가 | 무료 확정(19c 라이선스 매뉴얼 — 팩 대상은 V$ASH·DBA_HIST뿐). age-out 대비 자체 저장 |
| MongoDB | explain queryPlanner.queryHash/planCacheKey + $planCacheStats 폴링 | 8.0 planCacheShapeHash 개명 방어. 캐시 인메모리라 자체 스냅샷 필수 |

### 테마 B — p95 정직 등급 상향
- MySQL: events_statements_histogram_by_digest **스냅샷 차분**(TRUNCATE 불요) → 구간 p95(버킷 근사 표기) — 기존 잔여 해소
- MSSQL: query_store_runtime_stats의 avg+stdev로 ESTIMATED — **UNSUPPORTED 해제**(+runtime_stats_interval로 구간 통계, 활성 버킷 재집계 주의)
- PG: pg_stat_monitor 있으면 resp_calls 히스토그램으로 승격(HypoPG 대칭 "있으면" 패턴)
- Mongo: serverStatus opLatencies·$collStats latencyStats 히스토그램(프로파일러 불요) — 쿼리 단위가 아니라 인스턴스/컬렉션 단위이므로 **병행**
- Oracle: v$sqlstats에 분위수·표준편차 부재 재확인 — **UNSUPPORTED 유지가 정직**

### 테마 C — UNSUPPORTED 해제·저비용 고가치
- **PG 복제 슬롯 잔량**(pg_replication_slots wal_status/safe_wal_size) — 디스크 고갈 대표 장애 사각, SELECT 1개
- **PG 블로트 신호** — 이미 읽는 pg_stat_user_tables의 미사용 컬럼 4개(n_dead_tup 등) + pg_stat_progress_vacuum + pgstattuple_approx("있으면", 권한 pg_stat_scan_tables)
- **Oracle indexUsage 부분 해제** — DBA_INDEX_USAGE(12.2+, 켜는 것 없이 읽기만). SAMPLED 표본이라 "미사용≠삭제근거" 경고 필수
- **MySQL digest 안티패턴 컬럼** — SUM_NO_INDEX_USED·디스크 임시테이블·SORT_MERGE_PASSES·CPU/메모리(8.0.28+) 등 SELECT 확장
- MySQL 복제 심화 — replication_applier_status_by_worker(마이크로초·워커별·적용 에러), NTP 전제 clamp
- Mongo oplog window(local.oplog.rs 양끝) + flowControl + $currentOp 승격(idle 트랜잭션)

### 테마 D — 데드락 축 신설
- MSSQL: system_health XE **file target** 읽기(기본 존재·설정 변경 0, ring_buffer는 2022 빈결과 함정)
- MySQL: SHOW ENGINE INNODB STATUS의 LATEST DETECTED DEADLOCK 파싱(읽기 전용, 최근 1건 한계 표기)
- PG: pg_stat_database.deadlocks 카운터

### 테마 E — 관측 심화(선택)
- PG: pg_stat_io(16+), pg_stat_statements 미사용 컬럼(wal_*·temp_blks·jit), checkpointer(17 뷰 분리 분기), pg_wait_sampling "있으면"
- MSSQL: query_store_wait_stats(2017+, 쿼리 단위 대기)·session_wait_stats·tempdb PFS/GAM/SGAM 경합·memory grants·missing index DMV(신뢰도 캐비앗)
- Oracle: v$session_event(세션별 대기)·v$sys_time_model(DB Time 분해)·ASH-lite(v$session 폴링→자체 저장, SolarWinds DPA 선례로 팩 불요)·stale_stats Advisor·**dba_feature_usage_statistics로 팩 사용 라이선스 경고**(차별화)
- MySQL: metadata_locks(MDL 가해자)·에러 서머리·버퍼풀 통계·performance_schema.processlist(뮤텍스 프리, A9 부합)
- Mongo: top 명령·queryExecStats(콜스캔 카운터)·freeStorageSize

### 테마 F — 스케일 제어 (→ "프로덕션 로드맵 Phase 4"로 이관·갱신됨)

> 이 테마의 초기 항목(수집 병렬화·스케줄러 풀 분리·알림 레이트리밋·수집 토글·헬스 캐시)은
> **이미 코드 반영 완료**다("현재 직렬 루프"·"스레드 1개 공유" 서술은 낡음 — `SnapshotScheduler`
> 워커 풀·`SchedulingConfig` 풀 분리·`WebhookNotifier` 레이트리밋·`ScoreService` 캐시로 해소).
> 스케일의 최신 상태·잔여(노드 간 수집 샤딩·메타DB 파티셔닝·커넥션 온디맨드)는 **Phase 4 표가 SoT**.
> 수천 대(노드 샤딩·수집 에이전트 분리)는 여전히 설계만 — 범위 밖.

### 조사에서 확정된 "부적합" (재론 방지)
$queryStats(Atlas 게이트+internal 파라미터), Oracle AWR/ASH/DBA_HIST(팩)·Invisible/NOSEGMENT
인덱스(생성), MySQL setup_* 변경·TRUNCATE 리셋·OPTIMIZER_TRACE, PG auto_explain·pg_ash(대상에
기록)·track_io_timing 활성화, MSSQL blocked process report(sp_configure)·플랜 강제·QS 활성화 대행,
Mongo validate(exclusive 락)·FTDC 파싱(비문서화).
