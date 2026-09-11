# 대상 DB 최소 권한 모니터링 계정

DBTower가 대상 DB에 접속할 때 root/postgres/sa/SYSTEM 같은 관리자 계정을 쓸 필요는 없다.
이 문서는 5기종 각각에 전용 계정 `dbtower_monitor`(비밀번호 `dbtower1234`)를 만들고,
플랫폼의 각 기능이 실제로 요구하는 최소 권한을 **라이브 인스턴스에 대한 실측**으로 확정한 기록이다.

- 방법: 권한 없는 계정을 먼저 만들어 인스턴스로 등록하고, 기능별 API를 호출해 실패 시
  DB가 돌려주는 권한 에러 원문을 기록한 뒤, 부족한 권한을 하나씩 추가해 전 기능 통과 시점의
  집합을 최소로 확정했다. 아래 표의 모든 칸은 성공 응답 또는 권한 에러 원문이 근거다.
- 검증일: 2026-07-04, 검증 API: `POST /api/instances`(등록=health 검증),
  `GET query-stats / slow-queries / table-stats / replication`, `POST explain`
- 검증 버전: MySQL 8.4.10 / PostgreSQL 16.14 / SQL Server 2022 CU25 (16.0.4255.1) /
  Oracle Database Free 23.26 / MongoDB 7.0.37
- 재현 스크립트: `docker/mysql-init.sql`, `docker/postgres-init.sql`, `docker/mssql-init.sql`,
  `docker/oracle-init.sql`, `docker/mongo-init.js`

## 요약

| 기종 | 최소 권한 집합 |
|---|---|
| MySQL | `SELECT ON sample.*` + performance_schema 테이블 단위 `SELECT`(다이제스트·대기·락·인덱스 사용) + `sys` INVOKER 뷰 2개(원천 테이블 `SELECT`와 `sys` 함수 `EXECUTE` 포함) + `SELECT ON mysql.slow_log` + `REPLICATION CLIENT, REPLICATION SLAVE, PROCESS ON *.*` (전문은 아래, 2026-09-10 재실측) |
| PostgreSQL | `LOGIN` 롤 + `pg_read_all_stats` (+ EXPLAIN 대상 테이블 `SELECT`) |
| SQL Server | `VIEW SERVER PERFORMANCE STATE` 단 하나 |
| Oracle | `CREATE SESSION` + `SELECT_CATALOG_ROLE` + 대상 테이블 `READ` |
| MongoDB | `read@sample` + `clusterMonitor@admin` (admin db에 생성, authSource=admin) |

예상과 달랐던 점 세 가지:

1. MySQL에 `PROCESS` 권한은 필요 없었다. 통계 소스가 `SHOW PROCESSLIST`가 아니라
   performance_schema 테이블 SELECT이기 때문이다.
2. SQL Server 2022는 포괄 권한 `VIEW SERVER STATE`가 아니라 세분화된
   `VIEW SERVER PERFORMANCE STATE` 하나로 전 기능이 통과한다. 에러 메시지가 필요한
   권한명을 정확히 알려줬다.
3. MongoDB의 `read` 롤은 system.profile을 못 읽지만, `clusterMonitor`가
   `{ db: "", collection: "system.profile" }`에 대한 `find`를 갖고 있어 별도 커스텀 롤이
   필요 없었다 (아래 실측 참조).

---

## MySQL

### 기능 x 권한 (실측)

| 기능 | 필요한 권한 | 없을 때 실측 결과 |
|---|---|---|
| health (등록) | `SELECT ON sample.*` (기본 스키마 접속) | 등록 거부: `Access denied for user 'dbtower_monitor'@'%' to database 'sample'` |
| query-stats | `SELECT ON performance_schema.events_statements_summary_by_digest` | `SELECT command denied to user ... for table 'events_statements_summary_by_digest'` |
| latency 구간 p95 (NATIVE_WINDOWED) | `SELECT ON performance_schema.events_statements_histogram_by_digest` | 에러 없이 **누적값(NATIVE)으로 조용히 폴백** — 구간 p95만 못 냄(정직한 열화). 앱 로그에 WARN 1줄. 3차 아크 B-1에서 실측 발견 |
| slow-queries | `SELECT ON mysql.slow_log` (log_output=TABLE 전제) | `SELECT command denied to user ... for table 'slow_log'` |
| table-stats | `SELECT ON sample.*` (권한 없는 테이블은 information_schema.TABLES에 안 보임) | 에러는 없고 빈 결과 |
| explain | `SELECT ON sample.*` (EXPLAIN은 대상 테이블 SELECT 권한 요구) | (스키마 SELECT 부여 후 통과 확인) |
| replication | `REPLICATION CLIENT` (SHOW REPLICA STATUS) + `REPLICATION SLAVE` (SHOW REPLICAS 폴백) | 아래 에러 2건 |
| 최근 데드락 (D-2) | `PROCESS` (SHOW ENGINE INNODB STATUS) | `Access denied; you need (at least one of) the PROCESS privilege(s)` |
| wait-events, 심층 진단 대기 차분 (2026-09-10 재실측) | `SELECT ON performance_schema.events_waits_summary_global_by_event_name` + `SELECT ON performance_schema.setup_instruments` | 502 `SELECT command denied ... for table 'events_waits_summary_global_by_event_name'`. setup_instruments 하나만 빠져도 502 |
| sessions 블로킹 관계 blockedByPid (재실측) | `SELECT ON sys.innodb_lock_waits` + `SELECT ON performance_schema.data_locks`·`data_lock_waits` + `EXECUTE ON FUNCTION sys.format_statement`·`sys.quote_identifier` | 502 `SELECT command denied ... for table 'innodb_lock_waits'`. 뷰만 부여하면 `ERROR 1356 View 'sys.innodb_lock_waits' references invalid table(s) ... or definer/invoker of view lack rights` |
| 통계 수집 건강 advisor (재실측) | `SELECT ON performance_schema.prepared_statements_instances` | **HTTP 200**, 본문 점검 항목만 `status: ERROR` (`MySQL 통계 수집 건강 조회 실패`) |
| finops 인덱스 사용량 (재실측) | `SELECT ON performance_schema.table_io_waits_summary_by_index_usage` | **HTTP 200**, 본문 점검 항목만 `status: ERROR` (`MySQL 인덱스 사용 통계 조회 실패`) |
| 파티션 보조: 미사용 인덱스 (재실측) | `SELECT ON sys.schema_unused_indexes` (+ 위 table_io 테이블, INVOKER 뷰) | 코드 경로 기준으로 필요. sample에 파티션 테이블이 없어 API 차이는 관측하지 못했다 |
| 복제: 그룹 복제 토폴로지 (재실측) | `SELECT ON performance_schema.replication_group_members` | 코드 경로 기준으로 필요. 단일 노드라 API 차이는 관측하지 못했다 |

### 계정 생성 전문

```sql
CREATE USER IF NOT EXISTS 'dbtower_monitor'@'%' IDENTIFIED BY 'dbtower1234';
GRANT SELECT ON sample.* TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.events_statements_summary_by_digest TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.events_statements_histogram_by_digest TO 'dbtower_monitor'@'%'; -- 구간 p95(NATIVE_WINDOWED)
GRANT SELECT ON mysql.slow_log TO 'dbtower_monitor'@'%';
GRANT REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'dbtower_monitor'@'%';
GRANT PROCESS ON *.* TO 'dbtower_monitor'@'%'; -- 최근 데드락(SHOW ENGINE INNODB STATUS)
-- 2026-09-10 재실측으로 추가 (VERIFICATION 127절)
GRANT SELECT ON performance_schema.events_waits_summary_global_by_event_name TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.setup_instruments TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.prepared_statements_instances TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.replication_group_members TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.table_io_waits_summary_by_index_usage TO 'dbtower_monitor'@'%';
GRANT SELECT ON sys.schema_unused_indexes TO 'dbtower_monitor'@'%';
GRANT SELECT ON sys.innodb_lock_waits TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.data_locks TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.data_lock_waits TO 'dbtower_monitor'@'%';
GRANT EXECUTE ON FUNCTION sys.format_statement TO 'dbtower_monitor'@'%';
GRANT EXECUTE ON FUNCTION sys.quote_identifier TO 'dbtower_monitor'@'%';
```

### 실측 에러 원문 (권한 추가 전)

```
접속 실패로 등록 거부: Access denied for user 'dbtower_monitor'@'%' to database 'sample'

MySQL 쿼리 통계 수집 실패: SELECT command denied to user 'dbtower_monitor'@'172.21.0.1'
  for table 'events_statements_summary_by_digest'

MySQL 슬로우 쿼리 조회 실패: SELECT command denied to user 'dbtower_monitor'@'172.21.0.1'
  for table 'slow_log'

MySQL 복제 상태 조회 실패: Access denied; you need (at least one of) the SUPER,
  REPLICATION CLIENT privilege(s) for this operation

-- REPLICATION CLIENT 부여 후 SHOW REPLICA STATUS는 통과, 폴백 SHOW REPLICAS에서:
MySQL 복제 상태 조회 실패: Access denied; you need (at least one of) the
  REPLICATION SLAVE privilege(s) for this operation
```

### 특이사항

- `PROCESS` 불요 — performance_schema 다이제스트 테이블은 일반 `SELECT` 권한으로 읽힌다.
  `performance_schema.*` 전체가 아니라 요약 테이블 하나만 GRANT해도 query-stats가 통과함을 확인했다.
- `REPLICATION CLIENT`/`REPLICATION SLAVE`는 글로벌 권한이라 **접속 시점**에 평가된다.
  GRANT 후에도 커넥션 풀에 남은 기존 커넥션은 계속 거부되므로, 인스턴스를 삭제 후 재등록해
  풀을 새로 만들어야 반영됐다.
- slow-queries는 `SELECT SLEEP(1.5)`를 흘려 `mysql.slow_log`에 행이 쌓인 상태에서
  실제 데이터 반환까지 확인했다.

---

## PostgreSQL

### 기능 x 권한 (실측)

| 기능 | 필요한 권한 | 없을 때 실측 결과 |
|---|---|---|
| health (등록) | `LOGIN` 롤이면 충분 (PUBLIC의 기본 CONNECT) | - |
| query-stats | `pg_read_all_stats` | **에러 없이** 전 행이 `queryId=null`, `queryText="<insufficient privilege>"` |
| slow-queries | `pg_read_all_stats` (동일 뷰) | 동일한 마스킹 |
| table-stats | 추가 권한 불요 (`pg_stat_user_tables` + `pg_relation_size`) | - |
| explain | 대상 테이블 `SELECT` (`SELECT 1`은 권한 불요) | `ERROR: permission denied for table database_instance` |
| replication | 추가 권한 불요 (`pg_is_in_recovery()`, `pg_stat_replication` COUNT) | - |

### 계정 생성 전문

```sql
CREATE ROLE dbtower_monitor LOGIN PASSWORD 'dbtower1234';
GRANT pg_read_all_stats TO dbtower_monitor;
-- EXPLAIN 대상 테이블이 있으면 (sample DB에는 현재 테이블이 없다):
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO dbtower_monitor;
```

### 실측 기록

권한 없는 LOGIN 롤만으로 query-stats를 호출한 응답 (HTTP 200 — 이것이 함정이다):

```json
[{"queryId":null,"queryText":"<insufficient privilege>","calls":5163,
  "totalTimeMs":2677.74,"rowsExamined":5163,"loadPct":57.63}, ...]
```

`pg_read_all_stats` 부여 직후 (재접속 불필요 — 뷰가 호출 시점에 롤 멤버십을 평가):

```json
[{"queryId":"8207445343163765616","queryText":"SELECT pg_database_size($1)",
  "calls":5213,"totalTimeMs":2703.25, ...}]
```

EXPLAIN을 SELECT 권한 없는 테이블에 실행 (psql 직접 실측):

```
ERROR:  permission denied for table database_instance
```

### 특이사항

- **조용한 저하**: 다른 기종은 권한이 없으면 API가 에러를 돌려주지만, PostgreSQL의
  pg_stat_statements는 뷰 자체가 PUBLIC 조회 가능이라 에러 없이 텍스트만 마스킹된다.
  모니터링이 "동작하는 것처럼 보이는데 데이터가 없는" 상태가 되므로 반드시 계정 세팅 시 확인해야 한다.
- `pg_monitor`로도 통과하지만, 실측 결과 그 구성 롤인 `pg_read_all_stats` 하나로 충분했다
  (`pg_read_all_settings`, `pg_stat_scan_tables`는 현재 기능 집합에서 불요).
- table-stats가 빈 배열인 것은 권한 문제가 아니다 — 관리자 계정(postgres) 인스턴스로
  교차 확인한 결과 sample DB에 유저 테이블이 없어서 동일하게 `[]`였다.

---

## SQL Server

### 기능 x 권한 (실측)

| 기능 | 필요한 권한 | 없을 때 실측 결과 |
|---|---|---|
| health (등록) | 로그인만으로 충분 (master는 guest로 접속) | - |
| query-stats | `VIEW SERVER PERFORMANCE STATE` | `VIEW SERVER PERFORMANCE STATE permission was denied on object 'server', database 'master'` |
| slow-queries | 동일 (같은 DMV) | 동일 |
| table-stats | 동일 권한에 포함 (`VIEW DATABASE PERFORMANCE STATE`를 하위 포함) | `VIEW DATABASE PERFORMANCE STATE permission denied in database 'master'` |
| explain | `VIEW SERVER PERFORMANCE STATE` (플랜 캐시 DMV) | `VIEW SERVER PERFORMANCE STATE permission was denied ...` |
| replication | 동일 권한에 포함 (AlwaysOn DMV) | `VIEW DATABASE PERFORMANCE STATE permission denied in database 'master'` |
| 최근 데드락 (D-1) | `VIEW SERVER STATE` (sys.fn_xe_file_target_read_file — system_health XE 파일 타깃) | `VIEW SERVER STATE permission was denied` (성능 세분화 권한이 아니라 포괄 VIEW SERVER STATE 필요) |

### 계정 생성 전문

```sql
-- 'dbtower1234'는 기본 암호 정책 미달이라 CHECK_POLICY = OFF가 필요했다. 실측 에러:
-- Msg 33064: Password validation failed. ... not complex enough. The password must be
-- at least 8 characters long and contain characters from three of the following four sets:
-- Uppercase letters, Lowercase letters, Base 10 digits, and Symbols.
CREATE LOGIN dbtower_monitor WITH PASSWORD = 'dbtower1234', CHECK_POLICY = OFF;
GRANT VIEW SERVER PERFORMANCE STATE TO dbtower_monitor;
```

### 특이사항

- SQL Server 2022부터 `VIEW SERVER STATE`가 세분화됐고(`VIEW SERVER PERFORMANCE STATE` /
  `VIEW SERVER SECURITY STATE`), 에러 메시지가 세분화된 권한명을 그대로 알려준다.
  실측 결과 **GRANT 한 줄**로 5개 기능이 전부 통과했다 — 서버 수준
  `VIEW SERVER PERFORMANCE STATE`가 모든 DB의 `VIEW DATABASE PERFORMANCE STATE`를 하위 포함하기 때문.
- 등록 dbName이 master라서 별도 `CREATE USER`(DB 사용자 매핑) 없이 guest로 접속됐다.
  **유저 데이터베이스에 등록할 때는** `CREATE USER dbtower_monitor FOR LOGIN dbtower_monitor`가
  추가로 필요하다 (이번 환경에는 유저 DB가 없어 이 부분은 실측하지 못했다).
- 권한이 접속 시점이 아니라 문장 실행 시점에 평가되어, GRANT 직후 풀 재생성 없이 반영됐다.

---

## Oracle

### 기능 x 권한 (실측)

| 기능 | 필요한 권한 | 없을 때 실측 결과 |
|---|---|---|
| health (등록) | `CREATE SESSION`만으로 충분 (`v$version`은 PUBLIC 조회 가능) | - |
| query-stats | `SELECT_CATALOG_ROLE` (V$SQL) | `ORA-00942: 테이블 또는 뷰 "SYS"."V_$SQL"이(가) 존재하지 않습니다` |
| slow-queries | 동일 (V$SQL) | 동일 |
| table-stats | 추가 권한 불요 (`user_tables` — 단, 자기 스키마만 보임) | 에러 없이 빈 결과 |
| table-stats·테이블 상세·스키마 트리(앱 스키마 지정 시) | `SELECT_CATALOG_ROLE` (`dba_tables`·`dba_indexes`·`dba_segments`·`DBMS_METADATA.GET_DDL`의 다른 스키마) | 인스턴스 `appSchema`와 전역 `dbtower.oracle.app-schema`가 모두 비면 모니터 자신의 스키마만 봐서 "테이블을 찾을 수 없습니다"(VERIFICATION 132·133절) |
| explain | `EXPLAIN PLAN` 자체는 불요, 대상 테이블 `READ` 필요 | `ORA-00942: 테이블 또는 뷰 "SAMPLE"."USERS"이(가) 존재하지 않습니다` |
| replication | `SELECT_CATALOG_ROLE` (V$DATABASE) | `ORA-00942: 테이블 또는 뷰 "SYS"."V_$DATABASE"이(가) 존재하지 않습니다` |

### 계정 생성 전문 (FREEPDB1에서 SYSDBA로)

```sql
CREATE USER dbtower_monitor IDENTIFIED BY dbtower1234;
GRANT CREATE SESSION TO dbtower_monitor;
GRANT SELECT_CATALOG_ROLE TO dbtower_monitor;
GRANT READ ON sample.users TO dbtower_monitor;
```

### 특이사항

- `EXPLAIN PLAN FOR SELECT 1 FROM dual`은 `CREATE SESSION`만으로 통과했다 — 별도 PLAN_TABLE
  생성이 필요 없었다. Oracle이 SYS 소유 글로벌 임시 테이블(PLAN_TABLE$)을 PUBLIC 시노님으로
  제공하고 `DBMS_XPLAN.DISPLAY`도 PUBLIC 실행 가능이기 때문. 실제 테이블 대상 EXPLAIN에는
  그 테이블의 `READ`(또는 SELECT)가 필요하다 (위 ORA-00942가 근거).
- 접근 권한이 없는 객체는 권한 에러가 아니라 **ORA-00942 "존재하지 않습니다"**로 온다.
  V$SQL이 없다는 에러를 보면 존재 여부가 아니라 권한부터 의심할 것.
- `SELECT ANY DICTIONARY`(sample 계정에 부여돼 있음)까지는 불요 — `SELECT_CATALOG_ROLE`로 충분했다.
- 롤은 세션 시작 시점에 활성화되므로 GRANT 후 인스턴스 삭제·재등록으로 풀을 재생성해야 반영됐다.
- **트레이드오프**: 이 어댑터의 table-stats는 `user_tables`(자기 스키마) 기반이라 전용 모니터링
  계정으로는 항상 빈 결과다. 또 query-stats가 `parsing_schema_name = 현재 스키마`로 필터하므로
  dbtower_monitor가 파싱한 쿼리만 보인다. 대상 스키마의 쿼리·테이블 통계를 온전히 보려면
  스키마 소유 계정(sample)으로 등록하거나 어댑터가 dba_* 뷰를 쓰도록 바꿔야 한다.
  현재 운영 구성이 sample 계정으로 등록하는 이유가 이것이다.

---

## MongoDB

### 기능 x 권한 (실측)

| 기능 | 필요한 롤 | 없을 때 실측 결과 |
|---|---|---|
| health (등록) | 인증되는 계정이면 충분 (ping + buildInfo) | - |
| query-stats | `clusterMonitor@admin` (system.profile find) | `error 13 (Unauthorized): 'not authorized on sample to execute command { aggregate: "system.profile", ... }'` |
| slow-queries | `clusterMonitor@admin` | `error 13 (Unauthorized): 'not authorized on sample to execute command { find: "system.profile", ... }'` |
| table-stats | `read@sample` (listCollections + $collStats) | `error 13 (Unauthorized): 'not authorized on sample to execute command { listCollections: 1, ... }'` |
| explain | `read@sample` (대상 컬렉션 find) | `error 13 (Unauthorized): 'not authorized on sample to execute command { explain: { find: "users", ... } }'` |
| replication | `clusterMonitor@admin` (replSetGetStatus) | `error 13 (Unauthorized): 'not authorized on admin to execute command { replSetGetStatus: 1, ... }'` |

두 롤을 각각 단독으로 두고 교차 실측해 양쪽 다 필요함을 확인했다:
`read@sample`만 → profile/replication 실패, `clusterMonitor@admin`만 → table-stats/explain 실패.

### 계정 생성 전문 (admin db — 플랫폼이 authSource=admin을 가정)

```javascript
db.getSiblingDB('admin').createUser({
  user: 'dbtower_monitor',
  pwd: 'dbtower1234',
  roles: [
    { role: 'read', db: 'sample' },
    { role: 'clusterMonitor', db: 'admin' },
  ],
});
```

### 특이사항

- **핵심 실측**: `read` 롤은 비시스템 컬렉션만 읽을 수 있어 system.profile 접근이
  Unauthorized로 거부된다. 커스텀 롤을 만들 필요는 없었는데, `clusterMonitor`가
  이미 system.profile find를 갖고 있기 때문이다. `getRole("clusterMonitor",
  {showPrivileges:true})` 실측 결과에 다음 항목이 있다:

  ```javascript
  { resource: { db: '', collection: 'system.profile' }, actions: [ 'find' ] }
  ```

  즉 클러스터의 모든 db에 대한 system.profile 읽기가 clusterMonitor에 포함된다.
- 롤 변경은 즉시 반영됐다 (재등록 불필요 — 서버가 사용자 캐시를 무효화).
- 전제: 대상 db의 프로파일러가 켜져 있어야 한다 (`db.runCommand({profile: 2})` —
  docker-compose가 `--profile 2 --slowms 0`으로 기동).

---

## 워크벤치 콘솔 계정 (2026-09-10)

거버넌스 SQL 워크벤치는 모니터 계정을 쓰지 않는다. 사람이 여는 자유 조회는 인스턴스마다 따로 등록한 **콘솔 계정**으로만
실행된다(`PUT /api/instances/{id}/credentials/READ`, ADMIN). 플랫폼은 모니터 계정과 같은 이름을 거부하고, 저장 전에 실제로
접속해 본다. 재현 스크립트: `docker/workbench-{mysql,postgres,oracle}.sql`, `docker/workbench-mongo.js`.

| 기종 | 조회 계정(READ) | 변경 계정(WRITE, 승인된 티켓만 실행) |
|---|---|---|
| MySQL | `GRANT SELECT ON sample.*` | `GRANT SELECT, INSERT, UPDATE, DELETE, ALTER, INDEX ON sample.*` |
| PostgreSQL | `CONNECT` + `USAGE ON SCHEMA public` + `SELECT ON ALL TABLES` | 위 + `INSERT, UPDATE, DELETE` + `USAGE ON ALL SEQUENCES` + 소유 역할 멤버십 `GRANT sample_owner`(DDL용, 소유 역할에 `CREATE ON SCHEMA public` — 15+에서 인덱스 생성이 스키마 CREATE를 따로 본다) |
| Oracle | `CREATE SESSION` + 테이블별 `READ`(SELECT와 달리 `FOR UPDATE` 락을 못 건다) | `CREATE SESSION` + 테이블별 `SELECT, INSERT, UPDATE, DELETE`(DDL 없음) |
| SQL Server(Azure SQL Edge·SQL Server 2022 RTM-CU26 실측) | DB 사용자 매핑 + `SELECT ON SCHEMA::dbo` | 위 + `INSERT, UPDATE, DELETE ON SCHEMA::dbo` + `SHOWPLAN` + 테이블별 `ALTER`(인덱스·열 추가) |
| MongoDB | `read@sample` (admin db에 생성) | `readWrite@sample`(문서 사본을 트랜잭션 안에서 잡으므로 복제셋 필요, 인덱스 생성·삭제 포함) |

권한 변경 권한은 어느 계정에도 주지 않는다. 변경 계정의 DDL은 인덱스·열 추가 수준까지만 준다(MySQL `ALTER, INDEX`,
PostgreSQL은 테이블 소유자만 DDL을 하므로 로그인 불가 소유 역할의 멤버십). 승인된 티켓이라도 계정 권한 밖의 문장
(`DROP TABLE` 등)은 대상 DB가 거부한다 — 게이트·분류기 다음의 마지막 겹이다.

### 변경 계정이 권한을 쓰는 곳 (승인 티켓 실행, VERIFICATION 130절)

- 변경 전 행 사본은 `SELECT * ... FOR UPDATE`로 잡는다. 그래서 변경 계정에는 `READ`가 아니라 `SELECT`가 필요하다(Oracle).
- 기본 키는 JDBC 메타데이터(`getPrimaryKeys`)로 찾는다. 테이블을 볼 수 있으면 추가 권한이 없다.
- INSERT를 되돌리려면 생성된 키를 돌려받아야 한다. PostgreSQL `SERIAL`은 시퀀스 `USAGE`가 필요하고, MySQL은 키 값을
  직접 넣는 INSERT에서 키를 돌려주지 않아 그 경우 되돌리기가 닫힌다(실행 기록에 이유가 남는다).
- 검증 조회의 실행계획·응답시간은 변경과 같은 트랜잭션 안에서 변경 계정으로 잰다(MySQL·PostgreSQL `EXPLAIN`은 SELECT 권한,
  Oracle `EXPLAIN PLAN`은 세션의 PLAN_TABLE, SQL Server `SET SHOWPLAN_TEXT`는 DB 수준 `SHOWPLAN` 권한).
- SQL Server(VERIFICATION 131절 arm64 Azure SQL Edge, 132절 Rosetta VM의 실제 SQL Server 2022 RTM-CU26 16.0.4275.2 — 같은
  `docker/workbench-mssql.sql`에서 아래 거부·허용이 두 엔진 모두 한 줄도 다르지 않았다):
  - 조회 계정은 읽기 전용 겹이 없다. 드라이버가 `setReadOnly`를 무시하고 서버에 읽기 전용 트랜잭션이 없어, 쓰기 권한 계정으로 보낸 INSERT가
    거부되지 않고 끝의 롤백으로만 사라졌다. 조회 계정에서 쓰기 권한을 빼는 것이 이 기종의 조회 경계 전부다
    (`dbtower_reader`의 UPDATE: `The UPDATE permission was denied on the object 'customers'`).
  - 변경 계정의 DDL은 테이블 단위 `ALTER`만 준다. 인덱스·열 추가는 되고, `DROP TABLE`은 스키마 `ALTER`나 테이블 `CONTROL`이 필요해
    `Cannot drop the table 'orders', because it does not exist or you do not have permission`, `CREATE TABLE`은
    `CREATE TABLE permission denied in database 'sample'`로 거부됐다.
  - 삭제한 행을 같은 키로 되돌려 넣을 때 IDENTITY 열이면 `SET IDENTITY_INSERT`가 필요하고, 이는 그 테이블의 `ALTER` 권한을 요구한다.
    ALTER가 없는 테이블의 DELETE 되돌리기는 대상 DB가 거부한다(되돌리기 트랜잭션 전체가 롤백된다).
  - SQL Server 2022의 `VIEW SERVER PERFORMANCE STATE`는 SQL Edge(15.0 엔진)에 없어 모니터에는 이전 형태인 `VIEW SERVER STATE`를,
    스키마 트리·테이블 상세용으로 `VIEW DEFINITION ON SCHEMA::dbo`를 줬다(모니터는 행 SELECT가 거부된다). 실제 2022에는
    `docker/mssql-init.sql`의 `VIEW SERVER PERFORMANCE STATE`가 그대로 들어가고 `sys.dm_exec_query_stats` 조회가 통과했다.

### 민감 컬럼은 계정 권한으로 뺀다 (본 방어선)

워크벤치의 결과 마스킹은 컬럼 이름 기반이라 표현식(`email || ''`)은 원리상 못 잡는다. 반드시 가려야 하는 컬럼은 조회 계정의
컬럼 단위 권한에서 뺀다. 실측(VERIFICATION 128절):

```sql
-- PostgreSQL
REVOKE SELECT ON customers FROM dbtower_reader;
GRANT SELECT (id, name, phone, grade, created_at) ON customers TO dbtower_reader;
-- SELECT email || '' AS x FROM customers  ->  ERROR: permission denied for table customers
-- MySQL도 같은 형태: GRANT SELECT (id, name, grade) ON sample.customers TO 'dbtower_reader'@'%';
```

### 읽기 전용 트랜잭션은 드라이버마다 다르게 걸린다

콘솔 실행은 `setReadOnly(true)` 후 끝에 항상 롤백한다. 쓰기 권한 계정으로 직접 INSERT를 넣어 잰 결과:
MySQL은 드라이버 텍스트 검사 + 서버 `@@transaction_read_only=1`, PostgreSQL은 서버가 `read-only transaction`으로 거부,
**Oracle JDBC는 아무것도 하지 않아** 플랫폼이 `SET TRANSACTION READ ONLY`를 직접 건다(`ORA-01456`), SQL Server 드라이버는
힌트를 무시하므로 그 기종의 경계는 조회 계정 권한뿐이다. 그래서 조회 계정에서 쓰기 권한을 빼는 것이 모든 기종에 공통인 마지막 겹이다.

---

## 백업은 이 계정의 범위 밖이다

백업(`POST /api/instances/{id}/backup`)은 모니터링이 아니라 **관리 작업**이다.
mysqldump/pg_dump는 대상 테이블 전체 읽기+락, SQL Server `BACKUP DATABASE`는 db_backupoperator급
권한, Oracle DBMS_DATAPUMP는 DATA_PUMP_DIR 읽기/쓰기, mongodump는 전체 컬렉션 읽기를 요구한다.
이를 dbtower_monitor에 얹으면 "읽기 전용 모니터링 계정"이라는 경계가 무너지므로,
백업이 필요한 인스턴스는 **관리자(ADMIN) 계정으로 별도 등록**해서 쓴다. 모니터링 계정은
권한 부족으로 백업이 실패하는 것이 정상 동작이다.

## 근거 링크 (각 DBMS 공식 문서)

- MySQL 권한 목록 (REPLICATION CLIENT / REPLICATION SLAVE / SELECT):
  https://dev.mysql.com/doc/refman/8.4/en/privileges-provided.html
- MySQL 슬로우 쿼리 로그 (log_output=TABLE, mysql.slow_log):
  https://dev.mysql.com/doc/refman/8.4/en/slow-query-log.html
- PostgreSQL 사전 정의 롤 (pg_read_all_stats, pg_monitor):
  https://www.postgresql.org/docs/16/predefined-roles.html
- pg_stat_statements (권한 없으면 query가 <insufficient privilege>):
  https://www.postgresql.org/docs/16/pgstatstatements.html
- SQL Server 서버 권한 GRANT (VIEW SERVER PERFORMANCE STATE 포함 목록):
  https://learn.microsoft.com/en-us/sql/t-sql/statements/grant-server-permissions-transact-sql
- sys.dm_exec_query_stats 요구 권한:
  https://learn.microsoft.com/en-us/sql/relational-databases/system-dynamic-management-views/sys-dm-exec-query-stats-transact-sql
- Oracle 권한·롤 구성 (SELECT_CATALOG_ROLE):
  https://docs.oracle.com/en/database/oracle/oracle-database/23/dbseg/configuring-privilege-and-role-authorization.html
- MongoDB 내장 롤 (read는 비시스템 컬렉션만, clusterMonitor의 system.profile find):
  https://www.mongodb.com/docs/manual/reference/built-in-roles/
- MongoDB 프로파일러 관리:
  https://www.mongodb.com/docs/manual/tutorial/manage-the-database-profiler/
