# 심화 아크 2차 — 착수 명세 (구현 담당: Opus)

> 2026-07-07 5기종 전수 갭 조사(ROADMAP "심화 후보 백로그" 절, 근거 URL 포함)를 구현 가능한
> 명세로 구체화한 문서다. Phase D 착수 명세와 같은 원칙으로 쓴다 — 각 항목은 (1) 현재 갭과
> 코드 포인터, (2) 구현 방법(쿼리·클래스 수준), (3) 조사에서 검증된 함정, (4) 검증 기준(라이브
> 실측 시나리오 + 단위 테스트), (5) 산출물을 명시한다.

## 0. 공통 가드레일 (전 아크 적용 — 위반 시 해당 작업은 실패로 간주)

- **정체성**: 읽고 판단만. 대상 DB의 설정·데이터·객체를 바꾸는 코드 금지 (SET/ALTER/TRUNCATE/
  CREATE/UPDATE performance_schema 등 전부). "켜져 있으면 쓰고, 아니면 UNSUPPORTED" 게이트만 허용.
- **정직 표기**: 추정은 ESTIMATED, 표본은 SAMPLED, 버킷 근사는 그 사실을 응답 필드/문구로 명시.
  못 하는 기종은 UNSUPPORTED + 이유 1문장.
- **부하 원칙(A9)**: 새 수집은 기존 폴러 주기에 편승하거나 온디맨드. 무거운 조회(전 테이블 스캔류)는
  자동 수집 금지. 모든 JDBC 조회는 `jdbc()` 헬퍼 경유(타임아웃 상속).
- **실측 필수**: 모든 항목은 로컬 데모 스택(docker compose)에서 라이브 실측 후 VERIFICATION에
  절 번호로 기록(57절부터 사용). 스크린샷은 docs/images/webui/ (20번부터).
- **관례**: 커밋 한국어·AI 언급 금지·이모지 금지. Flyway는 **V9부터**(V8까지 사용됨). 테스트는
  기존 스위트에 추가하고 `./gradlew test` 그린 유지. 새 능력 = DbmsOperator 메서드 1개 추가
  또는 기존 재사용 원칙 유지.
- **병렬 작업 시**: git worktree + `arc/<이름>` 브랜치. **충돌 지대(순차 병합 필수)**:
  `DbmsOperator.java`, `PlanChangeTracker.java`, `app.js`(Promise.all 라인·렌더 함수),
  `index.html`(monitor-card 블록), `VERIFICATION.md`, `application.yml`. 서로 다른 기종의
  Operator 파일끼리는 병렬 안전.
- **로컬 실행 주의**: 8080은 다른 프로젝트(pay)가 점유 중일 수 있음 — `SERVER_PORT=8081` 사용.
  admin/devpass, viewer/viewpass. 데모 부하 시 메모리 킬 방지로 `JAVA_TOOL_OPTIONS=-Xmx1g`.
  회귀 감지 데모는 `SPRING_APPLICATION_JSON`으로 recent/baseline/cooldown/interval 축소
  (VERIFICATION 56절의 값 참고).

---

## 1차 아크 — "플랜 플립을 다섯 기종으로" (블로그 12편 예정)

목표: 현재 PG만 완전한 플랜 변경 감지(VERIFICATION 56절)를 4기종으로 확장 + PG 즉효 2종.
서사: "하나의 기능을 다섯 기종으로 완성하기" — 기종마다 획득 경로가 전부 다른 것이 핵심 소재.

### 현재 구조 (재활용 자산)

- `alert/PlanChangeTracker.java`: 회귀 감지된 쿼리만 `operator.explainNormalized(text)` →
  `shape()` 정규화 → SHA-256 해시 비교 → `PlanSnapshot`(V8) 저장. **플레이스홀더($1/?) 텍스트는
  PG가 아니면 스킵**하는 게 현재 갭(check() 상단 분기).
- `shape(DbmsType, plan)`: PG는 JSON 트리 walk(Node Type/Index Name/Relation Name만),
  그 외는 텍스트 숫자 제거 폴백.
- 기종별 queryId(= PlanSnapshot.queryId): MySQL=`DIGEST`, MSSQL=`query_hash`(varchar hex,
  MsSqlOperator.java:91), Oracle=`sql_id`, Mongo=system.profile의 `queryHash`.

### 설계 결정(공통): 플레이스홀더 해소를 explainNormalized 오버라이드가 아니라 "샘플/서버이력" 전략으로

기종마다 획득 경로가 달라서, `DbmsOperator`에 **새 default 메서드 1개**를 추가한다:

```java
/**
 * 플랜 변경 감지용 계획 원문 — 정규화 텍스트(플레이스홀더)로도 계획을 얻는 기종별 최선 경로.
 * PG는 GENERIC_PLAN(기존 explainNormalized), MySQL은 digest의 QUERY_SAMPLE_TEXT,
 * MSSQL은 Query Store 이력, Oracle은 plan_hash_value, Mongo는 profile 샘플 명령.
 * 반환: 계획 원문(또는 그에 준하는 식별 텍스트). 불가하면 Optional.empty() — 지어내지 않는다.
 */
default Optional<String> planForDigest(String queryId, String queryText) {
    // 기본: 플레이스홀더 없으면 explainNormalized 시도, 있으면 empty
}
```

`PlanChangeTracker.check()`는 explainNormalized 직접 호출 대신 `planForDigest()`를 쓰도록 변경.
PG 구현은 기존 경로 위임(동작 불변 — 기존 테스트 그대로 통과해야 함).

### A-1. MySQL — QUERY_SAMPLE_TEXT (Datadog DBM과 동일 방식)

- 방법: `MySqlOperator.planForDigest(digest, text)`:
  ```sql
  SELECT QUERY_SAMPLE_TEXT, QUERY_SAMPLE_SEEN
  FROM performance_schema.events_statements_summary_by_digest
  WHERE DIGEST = ? AND SCHEMA_NAME = ?
  ```
  → 샘플이 SELECT로 시작하고 절단 안 됐으면 기존 `explain()`(FORMAT=JSON)에 투입.
- **shape 개선(필수)**: MySQL explain은 JSON인데 현재 텍스트 폴백(숫자 제거)이라 노이즈가 큼.
  `shape()`에 MYSQL 분기 추가 — JSON walk로 `table.table_name`, `access_type`, `key`(인덱스명),
  중첩(`nested_loop`, `materialized_from_subquery` 등)만 직렬화. 예:
  `ALL(products)` vs `ref(products:idx_code)`.
- 함정(조사 검증됨): (a) 샘플이 `performance_schema_max_sql_text_length`(기본 1024B)에서 절단 —
  절단 감지(길이=상한 or EXPLAIN 문법 오류) 시 empty 폴백. (b) 샘플은 특정 파라미터 값의 플랜 —
  응답/알림에 "샘플 기반" 표기(PlanSnapshot.planShape 앞에 `sample:` 접두 or note). (c)
  `QUERY_SAMPLE_SEEN` 오래됨(기본 60초 내 갱신) — 너무 오래된 샘플이면 신선도 주의.
- 검증: sample DB의 `products`(이미 존재, code VARCHAR+idx_code)로 —
  `SELECT * FROM products WHERE code = '012345'` 부하(같은 digest) → 회귀 유도(윈도우 축소) →
  기준선 shape에 `idx_code` 포함 확인 → `DROP INDEX idx_code ON products` → 회귀 → 플립 알림에
  `ALL(products)` 확인. 단위: 절단 폴백, JSON walk shape("같은 구조 다른 cost = 같은 shape").

### A-2. SQL Server — Query Store 이력 (NATIVE, 게이트 필수)

- 게이트: DB별
  ```sql
  SELECT actual_state_desc FROM sys.database_query_store_options
  ```
  `READ_WRITE`/`READ_ONLY`가 아니면 empty(스킵 노트) — **켜는 행위(ALTER DATABASE) 절대 금지**.
- 방법: `MsSqlOperator.planForDigest(queryHashHex, text)`:
  ```sql
  SELECT TOP 1 p.query_plan
  FROM sys.query_store_query q
  JOIN sys.query_store_plan p ON p.query_id = q.query_id
  WHERE q.query_hash = CONVERT(binary(8), ?, 1)
  ORDER BY p.last_execution_time DESC
  ```
  (우리 queryId가 `CONVERT(varchar(64), query_hash, 1)`이므로 역변환 CONVERT(binary(8), ?, 1)
  가 동작하는지 실측 — 안 되면 query_hash 문자열 비교로 조정.)
- shape: query_plan은 showplan XML — MSSQL 분기 추가: `<RelOp>`의 `PhysicalOp`와
  `Object/@Index`만 트리 직렬화(XML 파싱은 기존 표준 라이브러리, 외부 의존 추가 금지).
- 보너스(같은 조인으로 공짜): `p.is_forced_plan=1`이면 shape에 `[FORCED]` 표기 — 강제 플랜 상태
  관측(강제 실행 자체는 안 함).
- 함정: 권한 VIEW DATABASE STATE(2022+는 VIEW DATABASE PERFORMANCE STATE) — least-privilege.md에
  추가. 2022 신규 DB는 기본 ON이지만 **복원/업그레이드 DB는 OFF일 수 있음** — 게이트가 곧 정직 표기.
- 검증: 데모 mssql에서 QS 상태 확인(로컬 컨테이너 master는 OFF일 수 있음 — 그러면 사용자 DB
  하나에서 확인하거나, OFF면 "게이트 동작(스킵 노트)" 자체를 실측으로 기록. QS가 켜진 DB에서
  인덱스 드랍 시나리오로 플립 1건).

### A-3. Oracle — v$sqlstats.plan_hash_value (무료 확정 — 19c 라이선스 매뉴얼 근거)

- 방법: `OracleOperator.planForDigest(sqlId, text)`:
  ```sql
  SELECT plan_hash_value FROM v$sqlstats WHERE sql_id = ? ORDER BY last_active_time DESC
  ```
  복수 행(= 복수 플랜 공존)이면 최신 것. 반환 텍스트는 `PHV:<값>` + (커서 생존 시)
  `DBMS_XPLAN.DISPLAY_CURSOR(sql_id, NULL, 'BASIC')` 본문 첨부(있으면).
- shape: Oracle 분기 — `PHV:<값>` 그대로 해시 대상(플랜 해시가 이미 정규화된 식별자).
  DISPLAY_CURSOR 본문은 planShape 표시용 보조.
- 함정: shared pool age-out 시 과거 플랜 소실 — 그래서 우리 PlanSnapshot 저장이 이력의 단일
  출처(기존 구조 그대로). v$sqlstats는 SELECT_CATALOG_ROLE로 조회 가능(기존 전제).
  **v$active_session_history·DBA_HIST는 어떤 이유로도 조회 금지(Diagnostics Pack).**
- 검증: 데모 oracle에 인덱스 있는 테이블 쿼리 → 회귀 유도 → 기준선 PHV → 인덱스 드랍 →
  PHV 변화 알림. (Oracle 부하 시나리오는 plan_demo 방식 재사용 — FREEPDB1.)

### A-4. MongoDB — system.profile 샘플 명령 재-explain

- 방법: `MongoOperator.planForDigest(queryHash, text)`: system.profile에서 해당 queryHash의
  최신 문서 1건을 찾아 `command` 필드(원문 명령)를 확보 → 기존 explain 경로(queryPlanner
  verbosity)로 재실행 → `queryPlanner.winningPlan` 트리 반환.
  ```
  db.system.profile.find({queryHash: ...}).sort({ts:-1}).limit(1)
  ```
- shape: Mongo 분기 — winningPlan의 `stage`와 `indexName`만 트리 직렬화
  (`FETCH>[IXSCAN(idx_k)]` vs `COLLSCAN`). `queryPlanner.planCacheKey`도 note로 병기(8.0의
  `planCacheShapeHash` 개명 대비 — 두 키 모두 읽는 방어).
- 함정: (a) 프로파일러 꺼져 있으면 샘플 없음 → empty(기존 전제와 동일한 정직). (b) command
  필드에서 explain 불가 명령(getMore 등) 필터. (c) 플랜 캐시는 인메모리 — 우리 저장이 이력.
- 검증: 데모 mongo 컬렉션에 인덱스 생성 후 부하 → 회귀 → 기준선 IXSCAN → dropIndex →
  COLLSCAN 플립. (인덱스 drop/create는 검증 시나리오용 — 데모 컨테이너에서만.)

### C-1. PG 복제 슬롯 잔량 감시 (SELECT 1개, 사각 해소)

- 방법: `PostgresOperator`의 replicationState() 확장 또는 신규 정보 —
  ```sql
  SELECT slot_name, active, wal_status, safe_wal_size,
         pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS retained_bytes
  FROM pg_replication_slots
  ```
  `OpsAlertDetector`에 규칙 추가: `wal_status IN ('unreserved')` WARN, `'lost'` CRITICAL,
  `safe_wal_size` 임계 미만 WARN(단 max_slot_wal_keep_size 미설정=-1이면 "무제한 보존" 정보 표기).
- 스탠바이에선 빈 결과 — 기존 role 분기와 합류. 권한 pg_read_all_stats로 충분.
- 검증: 데모 PG에 `SELECT pg_create_logical_replication_slot('dbtower_demo','test_decoding')`
  → 비활성 슬롯 상태 감지 확인 → `pg_drop_replication_slot`으로 정리(데모 검증용 생성·삭제는
  검증 스크립트에서만, 제품 코드는 조회 전용).
- UI: 복제 상태 카드에 슬롯 표(이름/active/wal_status/보존량) 추가.

### C-2. PG 블로트/VACUUM 신호 (이미 읽는 뷰의 미사용 컬럼)

- 방법: tableStats 또는 Advisor 전용 조회에
  `n_dead_tup, n_live_tup, last_autovacuum, autovacuum_count, n_mod_since_analyze` 추가 —
  Advisor 신규 2종: (a) dead ratio = n_dead_tup/(n_live_tup+n_dead_tup) > 0.2 AND n_dead_tup >
  10000 → "autovacuum이 못 따라감 의심"(ESTIMATED — 통계 추정치임 명시), (b) n_mod_since_analyze
  가 임계 초과 → "통계 노후 의심"(기존 stale-statistics Advisor와 통합 검토).
  세션 카드에 `pg_stat_progress_vacuum` 진행률(phase, heap_blks_scanned/heap_blks_total) 병기.
- pgstattuple은 이번 아크 범위 밖(있으면-패턴 2차 이후).
- 검증: 데모 PG에서 대량 UPDATE 후 dead ratio Advisor VIOLATIONS 확인, VACUUM 실행 중
  progress 노출 확인.

### 1차 아크 산출물 체크리스트

- [ ] DbmsOperator.planForDigest + 5기종 구현(PG는 위임), PlanChangeTracker 전환 — 기존 PG
      테스트·라이브 동작 불변 확인
- [ ] shape() 기종 분기 3종(MySQL JSON·MSSQL showplan XML·Mongo winningPlan) + 단위 테스트
      (같은 구조 다른 수치 = 같은 shape / 인덱스 변화 = 다른 shape, 기종별)
- [ ] MSSQL QS 게이트·Oracle PHV·MySQL 절단 폴백 단위 테스트
- [ ] C-1·C-2 Advisor/OpsAlert 규칙 + 단위
- [ ] 라이브 실측: 기종별 플립 1건씩(최소 MySQL·Oracle·Mongo 3종 + MSSQL은 QS 상태에 따라
      플립 또는 게이트 동작), PG 슬롯 감지, 블로트 Advisor — VERIFICATION 57·58절
- [ ] 스크린샷: 플랜 변경 카드에 여러 기종 항목(20번), 복제 카드 슬롯 표(21번)
- [ ] README 기능표 문구 갱신("PG는 GENERIC_PLAN" → 5기종 방식 요약), least-privilege.md에
      MSSQL VIEW DATABASE STATE 추가
- [ ] 블로그 12편 "하나의 기능을 다섯 기종으로 — 플랜 플립 완성" (개선 아크 서사: PG만 되던
      한계 → 기종별 획득 경로가 전부 다른 현실 → 함정(1024B 절단·QS 게이트·라이선스 경계) → 실측)

---

## 2차 아크 — "p95의 정직 등급을 올리다" (블로그 13편 예정)

### B-1. MySQL 구간 p95 — 히스토그램 스냅샷 차분 (기존 잔여 해소)

- 소스: `performance_schema.events_statements_histogram_by_digest`
  (BUCKET_NUMBER 0~449, BUCKET_TIMER_LOW/HIGH 피코초, COUNT_BUCKET 단조 증가 누적).
- 방법: `latencyPercentiles()`에서 상위 digest N개에 대해 —
  직전 스냅샷(인메모리 Map<digest, long[buckets]>, 인스턴스별)과 현재를 버킷별 차분 →
  구간 히스토그램 → 누적 95% 교차 버킷의 BUCKET_TIMER_HIGH를 p95로.
  source 라벨: 기존 NATIVE(누적 QUANTILE)와 구분해 **NATIVE_WINDOWED(버킷 상한 근사)** 신설,
  첫 호출(직전 스냅샷 없음)은 기존 누적 QUANTILE로 폴백 + "학습 중" 노트.
- 함정: (a) 서버 재기동 감지(uptime 또는 카운터 감소) 시 스냅샷 폐기. (b) TRUNCATE 절대 금지 —
  차분 방식이 그 대안임을 주석으로. (c) digest×450버킷 행 수 — WHERE DIGEST IN(상위 N)으로 제한.
  (d) statements_digest 소비자 기본 ON 전제 — 꺼진 환경은 빈 결과 → 기존 경로 폴백(정직 노트).
- 검증: 부하 전후 구간 p95가 누적 QUANTILE과 달리 "최근" 부하를 반영하는 것을 실측
  (예: 과거 느린 부하 후 현재 빠른 상태 — 누적은 높게, 구간은 낮게 나와야 함).

### B-2. MSSQL p95 — UNSUPPORTED 해제 (Query Store stdev, ESTIMATED)

- 게이트: A-2와 동일(actual_state).
- 방법: `MsSqlOperator.latencyPercentiles()` 구현 —
  ```sql
  SELECT TOP (?) q.query_hash, MAX(qt.query_sql_text) AS sample_text,
         SUM(rs.count_executions) AS calls,
         SUM(rs.avg_duration * rs.count_executions) / NULLIF(SUM(rs.count_executions),0) AS avg_us,
         MAX(rs.max_duration) AS max_us,
         -- 가중 결합 stdev는 근사: 최근 interval의 stdev 사용(정직 노트)
         MAX(rs.stdev_duration) AS stdev_us
  FROM sys.query_store_runtime_stats rs
  JOIN sys.query_store_plan p ON p.plan_id = rs.plan_id
  JOIN sys.query_store_query q ON q.query_id = p.query_id
  JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
  WHERE rs.runtime_stats_interval_id IN (최근 N개 interval)
  GROUP BY q.query_hash
  ```
  p95 ≈ avg + 1.645×stdev (max로 캡) — **ESTIMATED** 라벨(PG와 동일 규약), 단위 µs→ms 변환.
  최근 interval은 `sys.query_store_runtime_stats_interval`에서 end_time 기준 선택 —
  구간성(최근 N시간)이 dm_exec_query_stats 누적보다 나은 점을 노트로.
- 함정: 활성 interval은 plan×execution_type별 복수 행(in-memory+flushed) — 반드시 SUM/가중
  재집계(조사 검증). QS OFF면 기존 UNSUPPORTED 유지(게이트 노트).
- 검증: 데모 mssql(QS 켠 DB)에서 라벨 ESTIMATED·수치 sanity, QS OFF DB에서 UNSUPPORTED 게이트.

### B-3. Mongo — opLatencies·latencyStats 히스토그램 (프로파일러 무관 병행)

- 방법: (a) 인스턴스 층 — `runCommand({serverStatus:1, opLatencies:{histograms:true}})` →
  reads/writes/commands별 `{micros(2^n 하한), count}` 버킷. 재시작 누적이므로 **직전 스냅샷
  차분**(B-1과 동일 패턴) 후 보간 p95. (b) 컬렉션 층 — 기존 $collStats 호출에
  `latencyStats:{histograms:true}` 추가.
- 라벨: **NATIVE_HISTOGRAM(버킷 보간)** — 쿼리 단위가 아니라 인스턴스/컬렉션 단위임을 명시,
  기존 profile COMPUTED(쿼리 단위)는 유지·병행. 프로파일러 꺼진 인스턴스에서도 레이턴시 관측이
  생기는 것이 핵심 가치.
- 검증: 프로파일러 끈 상태에서도 인스턴스 p95가 나오는 것(기존엔 전멸) 실측.

### B-4. PG — pg_stat_monitor "있으면 승격" (HypoPG 패턴)

- 게이트: `SELECT 1 FROM pg_extension WHERE extname='pg_stat_monitor'`.
- 방법: 있으면 `pg_stat_monitor`의 `resp_calls`(버킷 배열)로 보간 p95, 라벨 NATIVE_HISTOGRAM.
  없으면 기존 ESTIMATED 유지. 데모 스택 검증을 위해 docker/postgres에 확장 추가 여부는
  구현자 판단(공식 이미지에 없으면 percona/postgresql 이미지 검토 — 데모 컨테이너 변경은 허용,
  대상 "고객" DB 변경 금지 원칙과 무관).
- Oracle: **하지 않는다** — v$sqlstats에 원자료 부재 재확인됨. UNSUPPORTED 유지가 이번 아크의
  정직성 대비군(블로그 소재).

### 2차 아크 산출물

- [ ] source 라벨 체계 정리: NATIVE / NATIVE_WINDOWED / NATIVE_HISTOGRAM / COMPUTED /
      ESTIMATED / AVG_FALLBACK / UNSUPPORTED — LatencyPercentile 주석과 UI 범례 갱신
- [ ] 단위: 히스토그램 차분·보간(경계값 스냅 확인), 재시작 폐기, MSSQL 재집계
- [ ] 실측: MySQL 누적 vs 구간 대비 실험, MSSQL 해제, Mongo 프로파일러-off 관측 —
      VERIFICATION 59절
- [ ] 스크린샷: 레이턴시 카드 라벨 다양화(22번)
- [ ] 블로그 13편 "정직 등급을 올리는 법 — 다섯 p95의 다섯 사다리" (Oracle은 못 올린 이유 포함)

---

## 3차 아크 — 데드락 축 + 선택 심화 (블로그 14편 후보)

### D-1. MSSQL 데드락 — system_health XE (설정 변경 0)

- 방법: `sys.fn_xe_file_target_read_file('system_health*.xel', NULL, NULL, NULL)`에서
  `object_name='xml_deadlock_report'` 필터 → XML에서 victim/process(입력 SQL)/resource 파싱.
  **file target 고정** — ring_buffer는 2022에서 빈 결과 사례(조사 검증).
- 노출: 신규 default 메서드 `List<DeadlockEvent> recentDeadlocks()` (default empty) →
  OpsAlert(새 데드락 발생 시 웹훅) + Monitoring 카드.
- 함정: 롤링 파일이라 "최근"만 — 정직 표기. VIEW SERVER STATE 필요.

### D-2. MySQL 데드락 — SHOW ENGINE INNODB STATUS

- `LATEST DETECTED DEADLOCK` 섹션 파싱(트랜잭션 2개·문장·victim). 최근 1건 한계 표기.
  출력 1MB 상한 주의. PROCESS 권한.

### D-3. PG — pg_stat_database.deadlocks 카운터 (누적 델타 알림)

### 선택(시간 남으면): MySQL metadata_locks(MDL 가해자), Mongo oplog window+flowControl,
Oracle v$session_event 세션별 대기, MySQL replication_applier_status_by_worker(NTP clamp 필수)

---

## 4차 아크 — Phase F 스케일 제어 (블로그 15편 후보)

1. **수집 병렬화**: SnapshotScheduler의 직렬 for → 고정 크기 ExecutorService(기본 4,
   `dbtower.snapshot.workers`) + 인스턴스별 시작 지터. ShedLock 의미(노드 단위 배타)는 유지 —
   병렬은 노드 안에서만. 백오프 맵 동시성 재점검(ConcurrentHashMap이지만 int[] 값 갱신 경합 →
   AtomicIntegerArray 또는 synchronized 블록).
2. **스케줄러 풀 분리**: `TaskScheduler` 빈(poolSize=4~8) — 폴러 7종의 head-of-line blocking
   해소(실측 근거: 절전 후 전체 폴러 동반 정지 사건).
3. **알림 폭주 제어**: WebhookNotifier에 전역 레이트리밋(분당 N) + 초과분은 묶음 요약 1건으로
   ("N건 추가 발생 — 대시보드 확인"). AI 분석 호출도 동일 게이트.
4. **인스턴스별 수집 제어**: DatabaseInstance에 `collectionEnabled`(V9) + 등록/수정 API·UI
   토글 — 문제 인스턴스 일시 격리 스위치.
5. **헬스 스코어 캐시**: 주기 계산(폴러) + 조회는 캐시 반환(집계 시각 표기 — 이미 UI에 있음).
6. 검증: 인스턴스 20개(동일 컨테이너 다중 등록으로 시뮬) + 느린 인스턴스 2개(toxiproxy 지연
   주입 검토)에서 수집 주기 준수 실측, 알림 폭주 시나리오.

---

## 실행 순서 요약

| 아크 | 내용 | Flyway | VERIFICATION | 블로그 |
|---|---|---|---|---|
| 1차 | 플랜 플립 5기종 + PG 슬롯·블로트 | (없음 — V8 재사용) | 57~58절 | 12편 |
| 2차 | p95 등급 상향 4종 | 없음 | 59절 | 13편 |
| 3차 | 데드락 축 + 선택 | 없음 | 60절 | 14편 |
| 4차 | 스케일 제어 | V9(collection_enabled) | 61절 | 15편 |

각 아크 완료 시: `./gradlew test` 그린 → 라이브 실측 → 스크린샷 → VERIFICATION/README/ROADMAP
갱신 → dbtower push → 블로그(개선 아크 서사) → gitblog push → 배포 200 확인 → 다음 아크.
