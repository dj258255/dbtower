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

### Phase B — DBA 진단 심화 (완료: B1~B8, VERIFICATION 41절)

시점 비교가 "무엇이 변했나"를 답한다면, B는 "지금 무엇이 막고 있나"와 "어떻게 고치나"를 답한다.
이전에 범위 밖이던 KDMS 본체 기능(Wait Event, Schema Diff)도 여기로 승격.

| # | 기능 | 내용 / 업계 근거 |
|---|---|---|
| B1 | **완료(26절)** Wait Event 분석 | DbmsOperator.waitEvents() 5기종 통합 — MySQL/MSSQL/Oracle 누적, PG 현재 스냅샷, Mongo 대기 큐. MySQL 비활성 instrument·MSSQL idle 필터를 정직하게 표기. REST+MCP(9종)+웹 카드 ([GitLab ASH](https://runbooks.gitlab.com/patroni/wait-events-analisys/)) |
| B2 | **완료(33절)** 블로킹 트리 + 세션 관리 | 5기종 activeSessions/killSession, PG cancel->terminate 2단계 라이브 완주, 블로킹 트리 blockedBy. kill은 ADMIN+감사, MCP 미노출. Mongo blockedBy N/A 정직 표기 |
| B3 | **완료(35절)** 인덱스 어드바이저 | PG HypoPG 가상 인덱스로 Cost 320->122 실측(ADVISED), 실제 인덱스 미생성. 타 기종 UNSUPPORTED 정직. SELECT 전용+식별자 방어 |
| B4 | **완료(38절)** 온라인 스키마 변경 | gh-ost 오케스트레이션(MySQL), 기본 dry-run·실행은 ADMIN confirm, 비밀번호 0600 conf. 라이브 noop+execute 완주. 비 MySQL UNSUPPORTED |
| B5 | **완료(39절)** 운영 알림 확장 | OpsAlertDetector(HA): 장기 idle-in-transaction·복제 지연·수집 정지. operator 변경 0(기존 메서드 재사용), 라이브 감지 확인 |
| B6 | **완료(40절)** 파라미터 드리프트 | parameters() 5기종+diff, 민감값 마스킹, ADMIN 제한. 라이브 PG 368·MySQL 623개 |
| B7 | **완료(36절)** Schema Diff | describeSchema() 5기종, 추가/삭제/변경 3분류, 기종혼합·상한 경고, Mongo 스키마리스 처리. MCP 12종 |
| B8 | **완료(34절)** 문의 채널 | 분석 결과(쿼리·플랜·규칙·AI·비고) 첨부해 웹훅 전송, alert 모듈(순환 회피), 미설정 sent:false. KDMS 1·2·3단계 완성 |

### Phase C — 프로비저닝 연동 (완료: VERIFICATION 42~45절)

지금은 "이미 존재하는 DB"를 수동 등록한다. 현업에서 DB는 IaC로 태어난다 —
태어나는 순간 관제탑에 자동 등록되는 것이 이 층의 목표다.

| # | 환경 | 방식 |
|---|---|---|
| C1 | **완료(43절)** Kubernetes | kind+CloudNativePG 1.24로 e2e 완주 — Cluster CR→프로비저닝→-app Secret→등록 Job(PUT)→health up(PostgreSQL 16.4). infra/k8s/ |
| C2 | **완료(44절)** 클라우드 | Terraform(OpenTofu) RDS 모듈 + local-exec 등록, validate 통과. apply는 AWS 자격증명 필요라 미실행(정직). infra/terraform/ |
| C3 | **완료(44절)** 온프레미스/VM | Ansible 플레이북 e2e 완주 — 최소권한 계정 생성+등록, 멱등 재실행 changed=0. infra/ansible/ |
| C4 | **완료(42절)** 멱등 등록 | PUT upsert — IaC 재실행 안전(같은 이름 갱신, 중복 0). 셋 다 이 종점을 공유 |

KDMS가 "DB 생성 자동화"를 본체 기능으로 갖는 이유가 이것이다 — 생성과 관제가 이어져야
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
- KDMS 원자료(docs/reference/kdms-meetup/ANALYSIS.md)의 자율 AI 사례와 일치: "CPU 100% 알람 10분
  지속 → AI가 모니터링+Wait Event 동시 조회 → 원인 진단". D3가 이것.
- 현대오토에버 DBA 우대사항 "문제 근본 원인 발견·해결 경험"에 정면 대응.

### 왜 변경 관리(SQL Review/승인)를 Phase D에 안 넣나 (정직한 범위 결정)

gh-ost·pt-osc·goInception이 전부 MySQL 전용이라 이기종 정체성과 충돌하고, 쓰기 경로 거버넌스는
읽기·진단이라는 DBTower DNA와 다른 카테고리다. 인상적이지만 초점을 흐린다 → "범위 밖" 유지.

| # | 기능 | 실존 제품 근거 | 재활용 자산 | 구현 명세 (Opus) | 검증 기준 |
|---|---|---|---|---|---|
| D1 | **완료(46절)** 이상 자동 감지 (베이스라인) | AWS DevOps Guru for RDS (DB Load 이상 감지) | QuerySnapshot 이력, ComparisonService(시점 비교), RegressionDetector(폴러·쿨다운·ShedLock) | 스냅샷 이력으로 인스턴스·쿼리별 **요일×시간대 베이스라인**(평균/표준편차 또는 분위수) 계산 → 폴러가 현재값을 베이스라인과 비교해 z-score/분위수 이탈을 이상으로 판정(고정 임계 +200%를 대체·보완). 신규 인스턴스는 데이터 부족 시 학습 중 표기. `insight` 모듈에 BaselineService + 이상 판정, `alert`가 소비 | 부하 스크립트로 평소→급증 만들고, 고정 임계 없이 "평소 대비 이탈"로 감지되는지 라이브. 단위: 베이스라인 계산·이탈 판정·데이터 부족 처리 |
| D2 | **완료(46절)** Advisors 자동 점검 | Percona PMM Advisors (Config/Perf/Query/Security 24h 스윕) | operations.md·least-privilege.md의 실측 규칙, parameters()(B6)·tableStats·describeSchema()(B7)·slowQueries | 규칙을 코드 Advisor로: digests_size 80% 포화, 스냅샷 보존 미설정, 위험 파라미터값(max_connections 과소 등), 미사용/중복 인덱스 후보, 권한 과다 계정, 통계 미수집 테이블. 5기종별 적용 가능한 것만(기종 무관은 UNSUPPORTED 표기). `advisor` 신규 모듈, 일일 스윕(@Scheduled+@SchedulerLock) + REST/웹 카드(심각도별) | 실 5기종에 스윕 돌려 실제 지적 나오는지(예: 스냅샷 보존 미설정 인스턴스 flag). 각 Advisor 단위 테스트(위반/정상) |
| D3 | **자연어 근본원인 진단 (AI 에이전트)** | KDMS "CPU 100%→AI가 도구 연쇄 진단", pganalyze AI-assisted | McpProtocolHandler(도구 12종), AiAnalyzer(claude CLI/SDK 백엔드), ai-analysis-rules.md | "왜 느려졌어?" 같은 질문 → AI가 **여러 MCP 도구(compare·wait_events·sessions·explain)를 스스로 연쇄 호출**해 근본원인 서술. 단발 분석(현 AiAnalyzer)을 **도구 사용 루프**로 승격. 판단 기준 문서를 시스템 프롬프트로(허위 금지·근거 없으면 모른다). 웹 콘솔에 질문 입력창, 답변에 사용한 도구·근거 표시 | 실제 부하 상황에서 질문→AI가 도구 2개 이상 엮어 원인(예: 신규 LIKE 풀스캔+wait io) 답하는지 라이브. 근거 없는 질문엔 "모른다" 확인 |
| D4 | **DB SLO / 에러 버짓** | Google SRE, DBRE(p95/p99·error budget·burn rate) | activity/query-stats(평균), health(가용성), 스냅샷 이력, **D4a의 백분위** | 인스턴스·핵심 쿼리별 SLI(p95/p99 레이턴시·가용성) 정의 → SLO 목표 → **에러 버짓 소진·번인 레이트** 대시보드. p95/p99는 D4a가 공급(기종별 가용성 다름). "인프라 지표(CPU) 아니라 사용자 경험 지표"(SRE 원칙) | 이력으로 SLI·SLO 대비 버짓 소진율 표시 라이브. 단위: SLI 계산·버짓 산식 |
| D4a | **완료(46절)** 레이턴시 백분위 (p95/p99) — 이기종 정직 | MySQL QUANTILE_95/99 컬럼, MongoDB profile 원샘플, "same metric different source"(추상화 논지) | QueryStat/QuerySnapshot 확장, 각 기종 operator | `latencyPercentiles()` 새 능력. **MySQL 8.0+**: events_statements_summary_by_digest의 QUANTILE_95/99/999 컬럼(피코초→ms). **MongoDB**: system.profile의 op별 원시 millis로 백분위 직접 계산. **PostgreSQL**: pg_stat_statements에 백분위 없음 → mean+1.645×stddev 근사를 "추정치(정규분포 가정, 꼬리 무거우면 과소평가)"로 명확히 라벨. **MSSQL/Oracle**: UNSUPPORTED(min/max/mean만). **한계 정직**: MySQL QUANTILE은 리셋 이후 누적이라 "윈도우 p95"가 아님 — 1단계는 누적 p95/p99, 2단계 옵션으로 events_statements_histogram_by_digest 두 스냅샷 차분 → 진짜 윈도우 백분위(히스토그램 수집 필요) | MySQL에서 실제 QUANTILE_95 값 반환·Mongo profile 계산값 라이브. PG는 "추정" 라벨 확인, MSSQL/Oracle UNSUPPORTED. 단위: 백분위 계산·근사 라벨·누적 한계 |
| D5 | **파티션 조회 (Partition Inventory)** | KDMS MCP 6기능 중 하나 | describeSchema()(B7) 확장, DbmsOperator | 기종별 파티션 목록·범위·크기 조회(MySQL/PG/Oracle 파티셔닝, Mongo는 샤딩/UNSUPPORTED). KDMS 갭 중 마지막 조각. **자동 관리(생성·삭제)는 범위 밖** — 조회만 | 파티션 있는 테이블로 목록 반환 라이브(없으면 빈 결과·UNSUPPORTED 정직) |
| D6 | **비용/효율 인사이트 (FinOps)** | AWS FinOps agent, Mydbops(미사용 인덱스로 34~43% 절감) | tableStats(크기), describeSchema()(인덱스), parameters() | 미사용/중복 인덱스 후보, 테이블 bloat, 오버프로비저닝 신호(연결 수 대비 max_connections 등)를 "낭비 후보"로. 실제 클라우드 과금 연동은 범위 밖(자격증명), 신호 제시까지 | 실 DB에서 미사용 인덱스 후보 실제 검출 라이브. UNSUPPORTED 정직 |
| D7 | **백업 신선도·커버리지 뷰** | 3-2-1 백업 원칙, DBA 일일 점검(모든 DB가 최근 백업됐나) | BackupRun 이력(확장1)·verifyRestore(A7) | 인스턴스별 마지막 백업 시각·복원 검증 상태·경과 시간을 한 화면에. 임계(예: 24h 초과 미백업) 넘으면 경보(B5 폴러에 규칙 추가). "백업했다"가 아니라 "지금 백업이 최신이고 복원 가능한가"를 상시 가시화 | 인스턴스별 마지막 백업·검증 상태 정확히 표시, 오래된 인스턴스 flag 라이브 |
| D8 | **통합 헬스 스코어** | 관측성 카테고리(자동 우선순위·클러스터), 설문(40%만 통합) | D1(이상)·D2(advisor)·D4(SLO)·health·D7(백업)을 합산 | 인스턴스마다 흩어진 신호(이상 개수·advisor 심각도·SLO 버짓·백업 신선도·health)를 **하나의 점수/등급**으로. 대시보드 상단에 5기종 전체를 한눈에, 나쁜 순 정렬. "40% 통합" 고통에 직접 대응 — 어디부터 볼지 기계가 우선순위 매김 | 여러 신호가 점수로 합산·정렬되는지, 문제 인스턴스가 상단에 오는지 라이브. 단위: 점수 산식 |

**구현 순서 권장 (Opus):** D1(이상 감지) → D2(Advisors) 를 먼저 — 둘이 "자율화"의 뼈대이고 기존
폴러/규칙 문서를 그대로 승격한다. 이어 D3(자연어 진단)로 채널·AI 자산을 루프로 엮으면 "스스로 보고
설명하는 관제탑"이 완성. **D8(통합 헬스 스코어)은 D1·D2·D4·D7이 신호를 내놓은 뒤 맨 마지막에** —
흩어진 신호를 합산하는 것이라 앞 항목들에 의존한다. D4·D5·D6·D7은 그 사이 선택 확장.
병렬 시 D1·D3(insight/alert·mcp)과 D2·D5·D6(advisor·operator), D7(backup)로 나누면 파일 충돌이 적다.
D8은 단독·마지막.

**정체성 가드레일:** Phase D의 모든 기능은 **읽고 판단**한다(쓰기·변경·승인 없음). 대상 DB를 바꾸지
않고, 5기종 통합을 유지하며, 못 하는 기종은 UNSUPPORTED로 정직하게 표기한다. 이 선을 넘으면(예: 자동
인덱스 생성, SQL 승인 워크플로) 그건 다른 제품이다.

## 범위 밖 (여전히 의도적으로 안 한다)

- **SQL 변경 심의·승인 워크플로 (Bytebase/Archery형)** — gh-ost류가 MySQL 전용이라 이기종 정체성과
  충돌하고, 쓰기 경로 거버넌스는 읽기·진단 DNA와 다른 카테고리. 인상적이지만 초점을 흐린다.
- **자동 인덱스 생성, 파티션 자동 관리** — Phase D는 "조회·조언"까지만(pganalyze의 "AI-assisted but
  developer-driven" 원칙). 대상 DB를 스스로 바꾸는 순간 다른 제품이 된다.
- DBaaS 수준의 멀티테넌시·과금·셀프서비스 포털(IDP) — 플랫폼 엔지니어링의 다른 제품 영역.
- Wait Event·Schema Diff·DB 생성 자동화는 범위 밖에서 로드맵(B·C)으로 승격했고, 이상 감지·Advisors·
  자연어 진단·파티션 조회는 Phase D로 승격했다. "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.
