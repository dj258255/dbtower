# Changelog

이 프로젝트의 주요 변경을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를
따르고, 버전은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따른다.

셀프호스트 이미지는 GHCR에 게시된다: `ghcr.io/dj258255/dbtower`. 태그를 push하면
`.github/workflows/release.yml`이 게시 전 테스트를 게이트로 걸고 멀티아치(amd64+arm64) 이미지를 올린다.

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
