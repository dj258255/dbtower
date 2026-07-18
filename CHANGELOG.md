# Changelog

이 프로젝트의 주요 변경을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를
따르고, 버전은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따른다.

셀프호스트 이미지는 GHCR에 게시된다: `ghcr.io/dj258255/dbtower`. 태그를 push하면
`.github/workflows/release.yml`이 게시 전 테스트를 게이트로 걸고 멀티아치(amd64+arm64) 이미지를 올린다.

## [Unreleased]

### Added
- 오브젝트 크기 주기 영속: V26 `size_snapshot` + 6시간 수집·7일 보존 잡 — lakehouse
  용량 예측(장기 D-day)의 원료. 단기 디스크 ETA(78절)와 지평 분리.
- 대기 이벤트 주기 영속(D1): V25 `wait_event_snapshot` + 5분 수집·7일 보존 잡 —
  lakehouse 장기 추세 분석의 공급원(첫 사이클 6인스턴스 134행 실측).
- 장기 베이스라인 수신·병합(D8): V24 `baseline_longterm`(lakehouse 되쓰기 수신) +
  BaselineService 가중 병합 — 요일x시간대 장기 통계를 QPS 축에 결합해 주간 계절성
  오탐을 줄인다(빈 테이블이면 현행 그대로, 회귀 0). `dbtower.baseline.longterm-enabled`.
- plan_snapshot 보존 시간 하한(D2): 카운트 스윕에 `retention-min-age-hours`(기본 48h)
  병행 — lakehouse D+1 하루창 추출 전 유실 차단.

### Added
- 커넥션 온디맨드(Phase 4) — minimumIdle 0 + idleTimeout 하한 가드(수집 주기+30s) + 장기 미사용 풀
  LRU 정리: 격리·저빈도 대상의 유휴 커넥션이 0으로 수렴(이전엔 대상마다 1개 영구 점유).
  SLO 헬스 폴러가 격리를 무시하고 핑하던 구멍도 해소
- query_snapshot 월별 RANGE 파티셔닝(V18) — 보존 정리가 벌크 DELETE(200만 행 1.9초+블로트)에서
  월 파티션 DROP(12.8ms·블로트 0)으로. 보존 잡이 파티션 선생성·DROP·걸친 구간 DELETE까지 관리,
  비파티션 환경은 DELETE 폴백(같은 계약 유지)
- 수집 샤딩(Phase 4) — dbtower.snapshot.shards=N: 샤드별 락을 각각 시도해 노드 여럿이면 수집을
  나눠 들고(자연 분산), 한 노드면 전 샤드 인수(무설정 페일오버). 기본 1은 현행과 완전 동일
- 로그인 잠금 카운터 메타 DB 이관(V17) — 인메모리(노드별 독립·재시작 소실)의 LB 뒤 임계 N배
  구멍 해소: 노드를 오간 실패도 하나의 임계로 잠긴다
- 서버 공유 인지(Phase 4 호스트 그룹핑) — 같은 host:port에 등록된 DB들의 서버 전역 신호(유휴
  트랜잭션·복제·데드락)를 그룹당 1회만 감지·경보(공유 인스턴스 명시), 호스트 스코프 Advisor(디스크
  예측)는 스윕에서 호스트당 1회(나머지 SHARED 표기), 콘솔 카드에 "서버 공유 ×N" 배지.
  위험 귀속(헬스 스코어·온디맨드 점검)은 dedup하지 않는다
- 디스크 포화 예측(Phase 5) — node_exporter 선형 추세로 "며칠 뒤 차는가"(ETA≤3일 치명/≤14일 경고,
  추세 없어도 여유<10% 경고). 인스턴스-노드 매핑 node_filter(V16, label="value" 셀렉터만 허용해
  PromQL 주입 방지), Prometheus 미설정 시 조용히 스킵. node-exporter compose를 정석(rootfs 마운트)으로
- LICENSE(Apache-2.0) + NOTICE — 번들 JDBC 드라이버(MySQL GPLv2+FOSS Exception·Oracle 독점 등)·
  이미지 번들 CLI 재배포 고지. 이전엔 라이선스 부재로 법적 재사용 불가였음
- DB팀 문의를 Discord 리치 embed로 — 필드 구조화(요청자·쿼리 sql 코드블록·실행계획·규칙·AI 분석),
  한도(256/1024/25) 경계 절단, Slack·미설정 텍스트 폴백, 레이트리밋 윈도우 공유
- 프로젝트 아이콘·파비콘 세트, 진단 흐름 다이어그램(식별→분석→문의) README 추가
- MCP OAuth 2.1 인가 서버(V20) — MCP 클라이언트가 브라우저 로그인으로 토큰 자동 발급.
  RFC 9728/8414 discovery + RFC 7591 동적 클라이언트 등록 + PKCE(S256) + refresh 회전, 기존
  로그인·유저 재사용. /mcp 전용 stateless 체인으로 401+WWW-Authenticate 자동 discovery

### Security
- 백업 산출물 저장 암호화(3-2-1-1-0), Vault 동적 자격증명으로 정적 비밀번호 유출 창 축소,
  Vault creds 경로를 database/creds/ 마운트로 봉인(임의 시크릿 접근 차단), Discord 인바운드
  Ed25519 서명·채널/유저 화이트리스트(기본 거부)
- 암호화 fail-closed를 배포 프로필 전체로 — 셀프호스트(docker 프로필)에서 DBTOWER_ENCRYPTION_KEY가
  없으면 기동을 거부한다(이전엔 prod 프로필만 막아 docker 경로가 평문 저장으로 뚫려 있었음, CWE-312).
  compose 수준 `${DBTOWER_ENCRYPTION_KEY:?}` 이중 방어
- 커밋됐던 바이너리 H2 DB(data/dbhub.mv.db, USERS/PASSWORD 테이블) 저장소에서 제거 + data/ gitignore
- 로그인 브루트포스 방어 — 계정별 연속 실패 시 잠금(기본 10회/15분), 잠긴 계정은 인증 앞에서 차단.
  로그인 화면에 남은 시간 표시
- 리버스 프록시 HTTPS 종단 지원 — forward-headers-strategy + 세션·CSRF 쿠키 Secure 토글(DBTOWER_COOKIE_SECURE)
- Prometheus 스크레이프 경로(/actuator/prometheus) 선택적 토큰 보호(DBTOWER_METRICS_TOKEN) — 미설정 시 현행+기동 WARN
- API 토큰 재시작 생존 — 미설정 시 매 기동 랜덤 재생성되던 것을 메타DB(platform_setting, V11)에 저장해
  재시작에도 동일 유지(MCP 연동이 재시작마다 깨지던 문제 해소)

### Added
- 테이블 상세 정보(5기종) — CREATE TABLE 전문·기본 통계(엔진·행수·데이터/인덱스 크기·평균 행 길이·
  생성 시각)·인덱스 정보(타입·카디널리티)를 상세 패널 아코디언과 API로. DDL은 NATIVE(MySQL SHOW CREATE
  TABLE·Oracle DBMS_METADATA)/RECONSTRUCTED(PG·MSSQL 카탈로그 재구성)/UNSUPPORTED로 출처를 정직 구분,
  미확보 카디널리티는 표시하지 않음. 식별자 주입 방어. POST /api/instances/{id}/table-detail.
  PG 재구성은 pg_get_constraintdef·pg_get_indexdef(엔진 자체 함수)로 FK·CHECK까지 정확히 담고,
  담지 못하는 트리거·파티션은 실제로 있을 때만 명시 — 배지에서 "(근사)"를 뗌
- 문의에 참조 테이블 구조 첨부 — 쿼리의 FROM·JOIN 테이블(조인 대상 포함)의 컬럼·인덱스·대략 행수를
  인덱스 중심으로 요약해 Discord/Slack 문의와 사이트 상세 패널에 표시(진단 핵심 재료). 존재하지 않는
  참조는 notFound로 정직 표기. POST /api/instances/{id}/referenced-schema
- 플랫폼 메타 DB 자기 백업 — pg_dump로 관제탑 자신의 상태 저장소를 주기 백업(로컬 + 원격 meta/),
  대상 DB만 백업하던 사각을 메움. PG 아니면 조용히 스킵
- CONTRIBUTING·CODE_OF_CONDUCT·이슈/PR 템플릿, README 시스템 요구사항 절, 스프링 부트 네이티브 롤링 파일 로깅

### Changed
- AI 분석 규칙 파일을 이미지에 번들(.dockerignore 예외 + Dockerfile COPY) — 이전엔 docs 제외로
  셀프호스트 이미지에서 AI 판정이 빈 프롬프트였음. compose에 ANTHROPIC_API_KEY 배선
- Spring Modulith internal 캡슐화 — 14개 모듈 전부 루트에는 공개 API(서비스·record)만 남기고
  구현을 internal/{web,domain,persistence,job}로 은닉. operator는 record 21종을 model/(@NamedInterface)로.
  타 모듈의 repository·entity 직접 참조 제거, ModularityTests가 경계 강제. docs/modules Documenter 재생성
- 문서 정리 — 중복 "ROADMAP 2.md"·손상된 PHASE-D-PLAN.md·보일러플레이트 HELP.md 삭제,
  AGENTS.md 모듈 트리 14개·Lombok 실제 정책 반영, README 로그인 안내 정합, DESIGN/HARDENING/
  deepening-spec에 완료 배너, ROADMAP에 프로덕션 로드맵(Phase 0~5)·셀프호스트 준비도 감사 추가
- 브랜딩 — 콘솔·로그인 헤더를 파비콘(favicon.svg) 로고 + "DBTower"로 통일하고 중복된 텍스트 "DB"
  마크 제거. 로그인 화면(미인증)에서도 파비콘이 뜨도록 SecurityConfig permitAll에 파비콘 자산 추가

### Added
- 팀 스코핑(LBAC, Phase 3) — 사용자에 팀 라벨(V14)을 달면 그 팀 인스턴스 + 라벨 없는 전역만 보인다.
  강제는 단일 경계(RegistryService.findAll/findById), 스코프는 로그인 authority로 부여(폴러·ADMIN은
  전역). 스코프 밖 단건은 미등록과 같은 404(존재 노출 방지). ADMIN이 PATCH로 팀 지정
- 공유 세션(spring-session-jdbc, V15) — 세션을 메타 DB에 저장해 앱 재시작·다중 노드에서 로그인 생존
- 로그 백업 5기종 전부 실동작 + PITR(Phase 2 완결) — MySQL binlog(FLUSH 경계), PostgreSQL WAL
  세그먼트(pg_switch_wal — wal_level·권한 게이트), MongoDB oplog(데모를 단일노드 replSet으로 전환),
  Oracle 아카이브 로그(ARCHIVELOG 게이트 + V$ARCHIVED_LOG 수집, PDB에선 ARCHIVE LOG CURRENT가
  CDB 수준 작업이라 best-effort — ORA-65040 실측 교훈), SQL Server BACKUP LOG. 백업 이력에 타입(V13)과 UNSUPPORTED 상태 신설("못 하는 것"≠"깨진 것").
  GET /api/instances/{id}/pitr-window — 복원 가능 창(마지막 FULL~마지막 LOG) + 기종별 복원 명령
  문안 생성(MySQL --stop-datetime·MSSQL STOPAT — 실행은 사람). MSSQL 실제 시점 복원 e2e 실증
- 데이터 마스킹 — 외부(웹훅·MCP 응답)로 나가는 SQL의 리터럴만 ?로 가림(식별자·구조 보존, 정규화
  텍스트엔 멱등). 회귀 알림·문의·MCP 에코 4곳 배선, AI 프롬프트는 mask-ai-prompt(기본 false) 토글
- 통계 수집 건강 Advisor — MySQL digest 포화율(80%)·Performance_schema_digest_lost(신규 쿼리 감지
  무력화)·Prepared Statement 익명 부하(digest 미집계 실측 근거), PG pg_stat_statements evict(dealloc)
  감시. 조치는 명령 안내까지만(대상 DB 변경 금지)
- 인스턴스 팀 라벨·콘솔 딥링크(V12) — team_label(Phase 3 LBAC와 컬럼 공유)·console_url(http/https만
  허용, 스킴 주입 차단). 카드 배지·회귀 알림·문의 embed에 담당 표기
- 알림 → 진단 딥링크 — dbtower.base-url 설정 시 회귀 알림에 콘솔 링크(인스턴스 선택+자연어 진단
  질문 프리필). metrics MCP 도구(14종째) — AI 진단이 CPU·Connections를 스스로 확인
- 모니터링 지표 통합 — Monitoring 탭에 CPU(%)·Connections 그래프 내장(Prometheus HTTP API 직접 조회,
  insight/internal PrometheusClient + GET /api/instances/{id}/metrics). CPU는 node_exporter 호스트 수준,
  Connections는 기종 exporter(MySQL threads_connected·PG numbackends). 미설정·미수집·미지원은 사유를
  그대로 표기(기능 게이트 — Prometheus가 없어도 콘솔은 정상). compose에 node-exporter 추가.
  시점 비교 드래그 그래프에 QPS ↔ CPU% 토글 — CPU 그래프 위에서 조회·비교 구간을 드래그로 선택

### Changed
- 문제 쿼리 식별 표 컬럼 보강 — Top Query 기본뷰에 Call/sec(스냅샷 차분)·평균 Latency(ms)·Row Examined(Avg),
  Slow Query에 User@host·Lock(ms)·Rows_sent(MySQL slow_log), MongoDB Slow Query에 Plan(IXSCAN/COLLSCAN,
  system.profile planSummary) 컬럼 추가. 미확보 필드는 "—"로 정직 표기(Call/sec는 스냅샷 이력 없으면 "—")
- 화면 패리티 마감 3건 — 비교뷰에 Load(시간 점유율) 증감 첫 컬럼 + Load 내림차순 정렬,
  Monitoring Metric 카드에 Query Activity(QPS) 그래프 병치, MongoDB Top Query(집계)에 Plan 배지
  (QueryStat.plan — Mongo profiler만 채움, 값이 있을 때만 컬럼 렌더)

### Fixed
- 웹 콘솔 타임존 스큐 — 앱 JVM은 UTC 고정(C-6)인데 프론트가 브라우저 벽시계(KST 등)를 그대로 보내
  활동 그래프·비교 조회가 9시간 미래의 빈 구간을 조회하던 것을 수정. 보낼 땐 toISOString(UTC),
  받을 땐 Z를 부여해 진짜 instant로 — 차트 축·드래그 선택·입력 표시는 브라우저 로컬로 일관
- 웹 콘솔 전체 백화 — 테이블 상세 추가 시 `const fmtBytes`가 기존 선언과 중복돼 app.js가 SyntaxError로
  파싱 중단, SPA가 아무것도 렌더하지 못하던 것을 수정(중복 제거, 음수 크기 "—" 표기는 헬퍼로 보존).
  Java 단위·curl API는 프론트 파싱을 안 거쳐 놓쳤고 브라우저 실물 확인이 잡음

### 업그레이드 노트 (암호화 fail-closed)
- 기존에 DBTOWER_ENCRYPTION_KEY 없이(평문 저장) docker로 운영하던 경우, 이번 버전부터 기동이 거부된다.
  절차: (1) `openssl rand -base64 32`로 키 생성해 .env에 설정 → (2) 기동 → (3) 기존 인스턴스는 평문으로
  저장돼 있으므로, 각 인스턴스를 웹 UI(또는 PUT /api/instances)에서 한 번 재저장하면 새 키로 암호화된다.
  (평문 행은 하위호환으로 계속 읽히지만, 키 설정 후 재저장 전까지는 암호화되지 않은 상태)

## [1.1.0] - 2026-07-07

v1.0.0으로 만들 만큼 만들었다 싶었는데, 실제로 쓰다 보니 필요한 게 계속 보였다. 심화 네 아크로
기존 기능을 다섯 기종에서 온전하게 다듬고, 그다음 만든 것을 스스로 감사해 하드닝했다. 상세 재현
기록은 [VERIFICATION.md](docs/VERIFICATION.md) 57~62절, 판단 근거는 [HARDENING-ROADMAP.md](docs/HARDENING-ROADMAP.md).

### Added
- **플랜 플립 감지 5기종화** — 실행계획 변경 감지가 PostgreSQL만 완전하던 것을 다섯 기종으로 확장.
  `DbmsOperator.planShapeForDigest`로 계획 획득 경로를 통일(MySQL `QUERY_SAMPLE_TEXT` 재EXPLAIN,
  SQL Server Query Store showplan, Oracle `v$sqlstats` plan_hash_value, MongoDB 프로파일러 명령
  재explain), `PlanShapes` 한 겹으로 JSON·XML·해시를 비교 가능한 형태로 정규화.
- **레이턴시 p95 source 등급 상향** — 라벨 신설 `NATIVE_WINDOWED`(히스토그램 두 스냅샷 차분 =
  최근 구간), `NATIVE_HISTOGRAM`(버킷 보간). MySQL 구간 p95, SQL Server Query Store 추정(ESTIMATED)
  해제, MongoDB `opLatencies` 히스토그램으로 프로파일러 무관 인스턴스 p95. Oracle은 원자료 부재로
  UNSUPPORTED 유지(정직성 대비군).
- **데드락 감지** — `recentDeadlocks`(SQL Server `system_health` XE, MySQL `SHOW ENGINE INNODB STATUS`),
  `deadlockCount`(PostgreSQL `pg_stat_database.deadlocks` 카운터 델타). `/api/instances/{id}/deadlocks`
  API + Monitoring 카드.
- **PG 복제 슬롯 감시** — 비활성 슬롯의 WAL 보존량 감시(디스크 고갈 사각) + 블로트/통계 노후 Advisor.
- **스케일 제어(Phase F)** — 수집 병렬화(워커 풀, ShedLock 노드 배타 유지), 스케줄러 풀 분리,
  알림 폭주 제어(분당 상한 + 초과분 묶음 요약), 인스턴스별 수집 격리 토글(`collectionEnabled`,
  `PATCH /api/instances/{id}/collection`), 헬스 스코어 노드별 캐시.
- **TLS 강제 접속** — 5기종 `useTls` 옵션(관리형 서비스 대응). 인증서 검증 우회 옵션은 의도적으로 없음.
- **백업 원격 보관** — S3 호환 오프사이트 업로드로 3-2-1 완성. 업로드 실패는 백업 실패로 취급하지 않음.

### Fixed
- **MySQL slowQueries sub-second 절삭** — `TIME_TO_SEC`가 정수를 반환해 1초 미만 쿼리가 0ms로
  보고되던 것을 `+ MICROSECOND(query_time)/1000` 보정으로 실측 ms 표기(예: 600.594ms).
- **MySQL 히스토그램 2^64-1 센티넬 오버플로** — `BUCKET_TIMER_HIGH`의 unsigned bigint 최댓값 센티넬을
  `getLong()`이 못 받던 것을 BigDecimal로 처리.
- **최소권한 계정 조용한 폴백** — 모니터 계정이 히스토그램 뷰 권한만 빠졌을 때 무음으로 누적값 폴백하던
  것을 경고 로그로 관측 가능화(+ `mysql-init` 권한 추가).
- **인스턴스 삭제 시 자원 누수** — 자식 테이블에 FK `ON DELETE CASCADE`(V10) + 삭제 이벤트로 인메모리
  상태(히스토그램 스냅샷·데드락 카운터·쿨다운·백오프) evict 배선.
- **커넥션 풀 경합발 허위 장애** — 인스턴스별 HikariCP 풀을 2→6으로 설정화(폴러 다수 동시 접근 시
  허위 백오프·"수집 정지" 경보 방지).
- **PG deadlockCount 형제 DB 사각** — `current_database()`만 보던 것을 클러스터 전체 SUM으로.
- **Oracle 통계 스키마 필터** — 모니터 계정 `CURRENT_SCHEMA`로 필터해 앱 SQL이 안 보이던 것을
  설정값(`dbtower.oracle.app-schema`) 기반으로. `plan_hash_value=0`(계획 미포착) 허위 플립 방지.
- **타임존 미고정** — `TimeZone.setDefault(UTC)` + `hibernate.jdbc.time_zone=UTC`로 노드 간 시각 일관성 확보.
- 스케줄러 시작 지터 상한, `Future.get` 예외 분리, `plan_snapshot` 보존 잡, 종료 가드 등.

### Security
- **XXE 방어 강화** — 데드락/showplan XML 파서 3곳에 `disallow-doctype-decl` +
  `FEATURE_SECURE_PROCESSING` 추가(CWE-611, 블라인드 SSRF 차단).
- **스택 쿼리 거부** — `requireSelect` 게이트가 `SELECT 1; DROP TABLE x` 같은 다중문을 거부(CWE-89).
- **암호화 fail-closed** — `prod` 프로필에서 암호화 키 미설정 시 기동 실패(평문 저장 방지, CWE-312).
- **에러 텍스트 마스킹** — 대상 DB 에러 원문 대신 일반 메시지 + errorId(CWE-209).
- **웹훅 멘션 인젝션 차단** — Discord `allowed_mentions: {parse: []}` + 제어문자 이스케이프.
- **SQL Server Azure 오탐 방지** — `EngineEdition=5`(Azure SQL DB)면 `system_health` 부재를 정직 처리.

## [1.0.1] - 2026-07-06

### Fixed
- **멀티아치 이미지** — v1.0.0의 amd64 단일 아치를 QEMU + `platforms: linux/amd64,linux/arm64`로
  멀티아치 게시(ARM 서버·Apple Silicon에서 에뮬레이션 없이 실행).

## [1.0.0] - 2026-07-06

첫 공개 릴리스. 이기종 DBMS 5기종(MySQL·PostgreSQL·SQL Server·Oracle·MongoDB)을 인터페이스 하나
(`DbmsOperator`) 뒤에서 등록·진단·백업·자율 감시하는 컨트롤 플레인.

### Added
- **5기종 지원** — 새 기종 = Operator 구현체 1개, 플랫폼 코드 0줄 수정(실측).
- **진단** — 쿼리 통계·시점 비교·회귀 감지·Wait Event·세션/블로킹·실제 실행 계획 심층 진단.
- **자율 진단** — 이상 감지(z-score)·Advisors·SLO/에러 버짓·헬스 스코어·자연어 진단(read-only MCP 도구 루프).
- **운영 안전** — 인증/인가(세션 + Bearer 토큰)·비밀번호 암호화(AES-256-GCM)·Flyway·보존 정책·
  ShedLock HA·감사 로그·백업 복원 검증(3값)·최소 권한.
- **프로비저닝 연동** — K8s(CloudNativePG)·Ansible·Terraform에서 생성 즉시 멱등 등록.
- **채널** — 웹 콘솔·MCP(stdio/HTTP)·웹훅.
- **셀프호스트 제품화** — 배터리 포함 컨테이너 이미지(백업 CLI 번들), 원커맨드 docker compose,
  태그 push가 곧 게시인 GHCR 파이프라인.

[1.1.0]: https://github.com/dj258255/dbtower/releases/tag/v1.1.0
[1.0.1]: https://github.com/dj258255/dbtower/releases/tag/v1.0.1
[1.0.0]: https://github.com/dj258255/dbtower/releases/tag/v1.0.0
