# 검증 기록 (실측 로그)

기능이 "실제로 동작함"을 실행 출력 그대로 남기는 실험 노트.
모든 출력은 로컬 docker compose 환경(MySQL 8.4 / PostgreSQL 16 / SQL Server 2022)에서의 실측이다.

## 1. 이기종 등록 + 헬스체크 (MVP1)

3종 등록 — 등록 시 실제 접속 검증을 통과해야 저장된다:

```
POST /api/instances (MYSQL)      -> {"id":1,"name":"local-mysql", ...}
POST /api/instances (POSTGRESQL) -> {"id":2,"name":"local-postgres", ...}
POST /api/instances (MSSQL)      -> {"id":3,"name":"local-mssql", ...}
```

헬스체크 — 기종별 버전·응답시간이 한 API로 나온다:

```
GET /api/instances/1/health
{"up":true,"version":"8.4.10","pingMillis":18,"message":"OK"}
GET /api/instances/2/health
{"up":true,"version":"PostgreSQL 16.14 ...","pingMillis":24,"message":"OK"}
GET /api/instances/3/health
{"up":true,"version":"Microsoft SQL Server 2022 (RTM-CU25) ...","pingMillis":29,"message":"OK"}
```

## 2. EXPLAIN + 규칙 기반 분석 (MVP3 일부)

PostgreSQL — 앞 와일드카드 LIKE로 Seq Scan 유발:

```
POST /api/instances/2/explain {"sql":"select * from pg_class where relname like '%abc'"}
findings: ["Seq Scan 발생 — 테이블 전체를 읽고 있습니다. WHERE 조건에 맞는 인덱스를 검토하세요"]
```

MySQL — 정렬 포함 풀스캔:

```
POST /api/instances/1/explain {"sql":"select * from information_schema.tables order by table_name"}
findings: [
  "테이블 풀스캔(access_type=ALL) — 인덱스가 없거나 타지 못하는 조건입니다",
  "filesort 발생 — ORDER BY가 인덱스로 해결되지 않아 별도 정렬을 수행합니다",
  "임시 테이블 생성 — GROUP BY/DISTINCT가 인덱스로 해결되지 않습니다",
  "인덱스 풀스캔(access_type=index) — 인덱스 전체를 훑고 있어 범위 조건 검토가 필요합니다"
]
```

안전 가드 — 관리 플랫폼은 대상 DB에 임의 DML을 실행하지 않는다:

```
POST /api/instances/1/explain {"sql":"delete from mysql.user"}
{"error":"EXPLAIN은 SELECT 쿼리만 허용합니다"}   [HTTP 400]
```

## 3. 시점 비교 (MVP2)

시나리오 — 레퍼런스 발표의 실사례 3종을 재현:

- Phase A(평소): 점조회 400회 (`SELECT * FROM users WHERE id=?`)
- Phase B(문제): 점조회 800회 + 신규 풀스캔 60회 (`... WHERE name LIKE '%777%'`, 8,000행 테이블)

```
GET /api/instances/1/compare?baseFrom=..T19:03:30&baseTo=..T19:05:00&targetFrom=..T19:06:30&targetTo=..T19:08:00

=== 구간 요약 ===
base   호출 804  | 총시간 34.86ms  | 평균 0.04ms | 읽은행 823
target 호출 1724 | 총시간 150.07ms | 평균 0.09ms | 읽은행 481685
증감 — 호출 +114.43% | 평균레이턴시 +125.0% | 읽은행수 +58427.95% | 신규쿼리 1개

=== 쿼리별 상위 ===
- SELECT * FROM users WHERE NAME LIKE ?   <<< 신규 쿼리
  QPS 0.0->1.0 | rows/call 0->8000        (풀스캔 신호)
- SELECT * FROM users WHERE id = ?
  QPS 6.67->13.33 (+100%)                 (호출량 급증 신호)
```

검출 결과 대응:

| 검출 | 레퍼런스 실사례 |
|---|---|
| 신규 쿼리 플래그 | 사례1: 신규 배포로 새 쿼리 유입 |
| QPS +100% | 사례2: 대규모 알림으로 호출량 급증 |
| rows/call 8,000 | 사례3: IN절 폭증으로 읽는 행수 급증 |

여기서 발견한 함정: 구간 경계가 스냅샷 배치 시각을 포함하지 않으면(1초만 늦어도)
발생량이 이전 배치에 흡수되어 delta가 0이 된다. 상세는 DESIGN.md 3.4절.

## 4. 보안 검증

JDBC URL 파라미터 주입 차단 (host 패턴 검증):

```
POST /api/instances {"host":"127.0.0.1?allowLoadLocalInfile=true", ...}
[HTTP 400]
```

H2 콘솔 비활성화 확인:

```
GET /h2-console -> HTTP 404
```

## 5. load 점유율 + 테이블 통계

쿼리 랭킹은 호출수가 아니라 시간 점유율(loadPct)로 — PMM QAN 방식:

```
GET /api/instances/1/query-stats?limit=5
 81.52% | calls=1 | SELECT @@SESSION.auto_increment_increment ...
  6.72% | calls=1 | SET character_set_results = ?
  ...
```

테이블 통계 — 행수는 통계 기반 추정치임이 그대로 드러난다:

```
GET /api/instances/1/table-stats
users rows=7926 data=376832B   (실제 행수 8,000 — InnoDB 통계 추정치)
GET /api/instances/3/table-stats
MSreplication_options rows=3 data=16384B ...
```

MySQL 컨테이너 재시작으로 performance_schema 누적 카운터가 리셋되는 것도 관찰 —
시점 비교의 delta 계산에 Math.max(0, ...) 클램프를 둔 이유.

## 6. 성능 개선 아크 1 — DriverManager vs HikariCP

before (매 수집마다 DriverManager 새 커넥션, 수집 1회 elapsedMs, n=12):

```
local-mysql:    avg=47.1ms  min=18  max=228
local-postgres: avg=34.1ms  min=24  max=88
local-mssql:    avg=49.5ms  min=31  max=146
```

after (인스턴스별 HikariCP 풀, max 2 / min idle 1, 수집 1회 elapsedMs, n=6):

```
local-mysql:    avg=19.2ms (풀 생성 첫 회 제외 avg=11.8ms) min=9  max=56  [56, 11, 10, 9, 11, 18]
local-postgres: avg=14.5ms (풀 생성 첫 회 제외 avg=14.4ms) min=10 max=19  [15, 19, 15, 10, 15, 13]
local-mssql:    avg=17.2ms (풀 생성 첫 회 제외 avg=14.6ms) min=13 max=30  [30, 17, 15, 14, 14, 13]
```

결과 (warm 기준):

| 인스턴스 | before | after | 개선 |
|---|---|---|---|
| MySQL | 47.1ms | 11.8ms | 4.0배 |
| PostgreSQL | 34.1ms | 14.4ms | 2.4배 |
| MSSQL | 49.5ms | 14.6ms | 3.4배 |

줄어든 것은 쿼리 시간이 아니라 매번 반복하던 TCP 연결 + 인증 핸드셰이크 비용이다.
각 배열의 첫 값(56/15/30)에 풀 생성 비용이 그대로 보인다.
풀 크기를 max 2로 작게 잡은 이유: 관제 도구가 대상 DB의 커넥션 슬롯을 점유하면 안 되기 때문.

## 7. 성능 개선 아크 2 — JPA saveAll vs JDBC batchUpdate

플랫폼 저장소를 H2에서 PostgreSQL로 이전(도그푸딩 목적, DESIGN.md 참고) 후 측정.
스냅샷은 불변 로그라 영속성 컨텍스트가 불필요 — JDBC batchUpdate + reWriteBatchedInserts=true로 교체.

before (JPA saveAll, 행마다 INSERT, n=6):

```
local-postgres: avg=83.3ms rows(avg)=55  [59, 70, 73, 96, 55, 147]
local-mysql:    avg=16.3ms rows(avg)=7   [11, 17, 15, 25, 12, 18]
local-mssql:    avg=6.5ms  rows(avg)=3   [1, 4, 3, 4, 7, 20]
```

after (JDBC batchUpdate, n=6):

```
local-postgres: avg=3.5ms rows(avg)=32  [4, 4, 2, 3, 3, 5]
local-mysql:    avg=5.7ms rows(avg)=6   [7, 4, 8, 5, 4, 6]
local-mssql:    avg=1.2ms rows(avg)=2   [0, 2, 1, 1, 1, 2]
```

중간에 dbid 필터 수정으로 배치당 행수가 달라져(55->32), 행당 비용으로 비교한다:

| 인스턴스 | before | after | 행당 개선 |
|---|---|---|---|
| local-postgres | 1.51ms/행 | 0.11ms/행 | 13.8배 |
| local-mysql | 2.33ms/행 | 0.95ms/행 | 2.5배 |

## 8. 수집 중 발견한 정합성 버그와 격리 원칙 검증

pg_stat_statements는 클러스터 전역 뷰다 — dbid 필터 없이 조회하면
같은 클러스터의 다른 데이터베이스 쿼리까지 섞여 통계가 오염된다.
sample과 dbtower(플랫폼 자체 DB)가 서로의 쿼리를 보고 있던 것을 발견해
`WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())` 필터를 추가했다.

또 pg_stat_statements 확장은 데이터베이스 단위 설치라, dbtower DB에 확장이 없어
dbtower-self 수집만 실패했다:

```
WARN 스냅샷 수집 실패 instance=dbtower-self cause=... relation "pg_stat_statements" does not exist
INFO 스냅샷 수집 완료 instance=local-mysql ...      (다른 인스턴스는 정상 수집 계속)
```

한 인스턴스의 실패가 나머지 수집을 막지 않는 격리 원칙이 실제 장애에서 동작함을 확인.
CREATE EXTENSION 후 자기 수집 성공:

```
INFO 스냅샷 수집 완료 instance=dbtower-self rows=49 collectMs=4 saveMs=3
```

플랫폼이 자기 자신을 관리 대상으로 등록해 감시하는 도그푸딩 구성 완료 (id=4 dbtower-self).

## 9. 성능 개선 아크 3 — 도그푸딩: DBTower로 DBTower 자신을 진단해 개선

시점 비교가 읽는 스냅샷 테이블은 의도적으로 인덱스 없이 시작했다.
벤치마크용 합성 데이터 50만 행을 백필한 뒤(합성임을 명시함), DBTower 자신의 explain API로
자기 시점 비교 쿼리를 진단했다.

before — DBTower의 규칙 분석기가 자기 쿼리의 풀스캔을 지적:

```
POST /api/instances/4/explain  (instance 4 = dbtower-self, 플랫폼 자체 DB)
{"sql":"SELECT * FROM query_snapshot WHERE instance_id = 1 AND captured_at BETWEEN ... ORDER BY captured_at"}
findings: ["Seq Scan 발생 — 테이블 전체를 읽고 있습니다. WHERE 조건에 맞는 인덱스를 검토하세요"]

EXPLAIN ANALYZE (50만 행):
Parallel Seq Scan on query_snapshot ...
Execution Time: 21.269 ms
```

개선 — instanceId 등치 + capturedAt 범위 조건이므로 등치 컬럼을 선두에 둔 복합 인덱스:

```sql
CREATE INDEX idx_snapshot_instance_time ON query_snapshot (instance_id, captured_at);
```

after — 같은 API로 재진단:

```
findings: ["규칙에 걸린 비효율 신호가 없습니다"]

EXPLAIN ANALYZE:
Index Scan using idx_snapshot_instance_time on query_snapshot
  Index Cond: ((instance_id = 1) AND (captured_at >= ...) AND (captured_at <= ...))
Execution Time: 0.062 ms
```

| | before | after | 개선 |
|---|---|---|---|
| 접근 경로 | Parallel Seq Scan | Index Scan | |
| 실행 시간 (50만 행) | 21.269ms | 0.062ms | 343배 |

플랫폼이 제공하는 진단 기능이 플랫폼 자신의 병목을 찾고, 고친 결과를 같은 기능으로 재검증했다.

## 10. 성능 개선 아크 4 — max_digest_length 1024 vs 4096 실측

MySQL digest는 max_digest_length(기본 1024)까지만 정규화한다.
앞부분이 동일한 긴 쿼리들이 하나의 digest로 뭉개지는 문제를 side-by-side로 재현했다.

구성: 기본 설정(1024) MySQL 컨테이너를 임시로 추가해, 우리 설정(4096)과 같은 쿼리 쌍을 실행.
쿼리 쌍: 동일한 프리픽스(AND name = ? 반복 200회) + 꼬리만 다름
(`AND city IS NOT NULL` vs `AND name IS NOT NULL`), 원문 4,260자.

```
-- 기본값 1024 서버
executions=2  len=1646  tail="NAME = ? AND NAME = ?"        <- 두 쿼리가 1개 digest로 병합, 꼬리 소실
-- 우리 설정 4096 서버
executions=1  len=2670  tail="AND `city` IS NOT NULL"        <- 구분 유지
executions=1  len=2668  tail="AND NAME IS NOT NULL"
```

의미: 기본값에서는 "둘 중 어느 쿼리가 문제인지" 식별이 불가능해진다.
레퍼런스가 4096으로 상향한 것과 같은 판단 — 우리는 docker-compose에서 처음부터 반영했다.

부수 발견: 절단 기준은 정규화 텍스트 길이가 아니라 토큰 버퍼 바이트다.
원문 1,740자(반복 80회) 쿼리는 정규화 텍스트가 1,108자여도 잘리지 않았고,
반복 200회로 늘려서야 1024 서버에서 절단이 발생했다.

메모리 영향: performance_schema_max_digest_length 상향은 digest 테이블 행당 텍스트 컬럼이
커지는 것 — 기본 1만 행 기준 약 3KB x 10,000 = 수십 MB 수준의 증가로, 식별 불가 리스크와
맞바꿀 만한 비용이라 판단했다.

PostgreSQL은 이 이슈가 없다 — 텍스트가 아니라 쿼리 파싱 결과 기반으로 digest를 만들기 때문.
같은 "쿼리 통계"도 기종 내부가 이렇게 다르다 (DbmsOperator 추상화가 필요한 또 하나의 근거).

## 11. 성능 개선 아크 5 — k6 부하 테스트

조회 계열 API 5종(목록·헬스체크·쿼리통계 x2·테이블통계)을 무작위로 때리는 시나리오.
쿼리통계/테이블통계는 매 요청이 대상 DB를 실시간 조회한다(캐시 없음).

```
k6: 10 VU, 30s
http_reqs:         84,991  (2,832 req/s)
http_req_duration: avg=3.48ms  med=3.37ms  p(90)=5.29ms  p(95)=5.86ms  max=93.57ms
checks_succeeded:  100.00% (0 failed)
```

매 요청이 대상 DB 왕복임에도 P95 5.86ms를 유지한 것은 아크 1(HikariCP)의 효과가 크다 —
풀 없이 매 요청 새 커넥션이었다면 요청당 수십 ms의 핸드셰이크가 더해졌을 것이다.

## 12. 확장1 — 백업 정책: 추상 정책이 기종별 실행 방식으로 갈라진다

"N분 주기 전체 백업"이라는 추상 정책 하나가 기종마다 완전히 다른 실행 모델로 번역된다:

| 기종 | 실행 모델 | 방식 |
|---|---|---|
| MySQL | 클라이언트 도구 | mysqldump --single-transaction (MVCC 스냅샷으로 락 없이) |
| PostgreSQL | 클라이언트 도구 | pg_dump (비밀번호는 인자가 아닌 PGPASSWORD 환경변수) |
| SQL Server | 서버 사이드 SQL | BACKUP DATABASE ... TO DISK — 외부 도구 없이 서버가 직접 파일 기록 |

첫 실행은 2/3이 실패했다 — 실전 운영에서 마주치는 이슈가 그대로 나왔다:

```
MySQL  FAILED: Can't connect to MySQL server on '127.0.0.1:13306'
  -> mysqldump가 컨테이너 안에서 실행되는데 호스트 관점 주소를 넘겼다 (네트워크 관점 차이)
PG     FAILED: server version: 16.14; pg_dump version: 14.19 - version mismatch
  -> 호스트 pg_dump(14)가 서버(16)보다 낮으면 거부된다 (클라이언트-서버 버전 호환)
MSSQL  SUCCESS (서버 사이드라 도구·버전 이슈 자체가 없음)
```

해결: 백업 명령을 {host} {port} {user} {password} {db} 플레이스홀더 템플릿으로 바꿔,
실행 위치(호스트/컨테이너/에이전트)마다 달라지는 접속 관점을 설정이 흡수하게 했다.

수정 후 3종 전부 성공:

```
POST /api/instances/1/backup -> SUCCESS 153ms  mysql-....sql (214,330 bytes)
POST /api/instances/2/backup -> SUCCESS 168ms  postgres-....sql (165,621 bytes)
POST /api/instances/3/backup -> SUCCESS 130ms  (server) /var/opt/mssql/data/....bak
```

정책 자동 실행 — 1분 주기 정책을 걸자 폴러가 정확히 1분 간격으로 실행:

```
PUT /api/instances/1/backup-policy {"intervalMinutes":1,"type":"FULL"}
backup-runs:
  02:54:51 SUCCESS 102ms
  02:55:51 SUCCESS  92ms
  02:56:52 SUCCESS 110ms
```

로그 백업(LOG)은 기종별로 요구 구성이 달라(binlog/WAL 아카이빙, 복구 모델) FULL만 구현하고
나머지는 명시적 UnsupportedOperationException으로 남겼다.

## 13. 확장2 — 복제 상태 통합 뷰 + Prometheus/Grafana

복제 상태도 기종별 소스가 전부 다르다 — 하나의 모델(role/lagSeconds/detail)로 통합:

| 기종 | 소스 | 역할 판정 |
|---|---|---|
| MySQL | SHOW REPLICA STATUS / SHOW REPLICAS | 레플리카면 Seconds_Behind_Source가 지연 |
| PostgreSQL | pg_is_in_recovery() / pg_stat_replication | recovery 모드면 재생 지연 계산 |
| SQL Server | sys.dm_hadr_database_replica_states | AlwaysOn 미구성이면 행 없음 |

```
GET /api/instances/1/replication -> {"role":"STANDALONE","lagSeconds":0.0,"detail":"복제 구성 없음"}
GET /api/instances/2/replication -> {"role":"STANDALONE","lagSeconds":0.0,"detail":"복제 구성 없음"}
GET /api/instances/3/replication -> {"role":"STANDALONE","lagSeconds":0.0,"detail":"AlwaysOn 가용성 그룹 미구성"}
```

로컬은 단일 인스턴스 3대라 전부 STANDALONE으로 정확히 판정된다.
(복제 토폴로지를 실제로 꾸며 lag을 보는 것은 향후 과제)

모니터링 스택 — exporter + Prometheus + Grafana, 플랫폼 자신의 메트릭도 함께 수집:

```
Prometheus 타깃 (전부 up):
  dbtower      up  http://host.docker.internal:8080/actuator/prometheus  (JVM·HTTP·HikariCP 풀)
  mysql      up  http://mysqld-exporter:9104/metrics
  postgres   up  http://postgres-exporter:9187/metrics

PromQL 확인:
  mysql_global_status_threads_connected = 2

Grafana: 11.1.0 database ok (datasource 프로비저닝 자동)
```

MSSQL exporter는 표준(prometheus 공식/커뮤니티 주류)이 없어 제외 — 서드파티 의존을 늘리는
것보다 플랫폼의 queryStats/tableStats API로 커버하는 편이 낫다고 판단.

## 14. 백업 보안 보강 (자동 보안 리뷰 지적 반영)

- 명령 템플릿: 치환 후 split -> 토큰 분리 후 토큰 내 치환으로 변경 (공백 값으로 인자 주입 방지)
  + 치환 값 허용 문자 검증([A-Za-z0-9._-]) + "-" 시작 값(플래그 주입) 거부
- 비밀번호를 argv에서 제거: mysqldump는 MYSQL_PWD, pg_dump는 PGPASSWORD 환경변수로만 전달
  ({password} 플레이스홀더 자체를 금지 — 실수로도 argv에 못 싣게)
- MSSQL 식별자: dbName의 ]를 ]]로 이스케이프(대괄호 탈출 방지), 백업 파일명은 안전 문자만 허용

## 15. 확장3 — 쿼리 회귀 자동 감지 + 웹훅 알림 + AI 1차 분석

시점 비교를 사람이 구간을 고르는 대신 플랫폼이 스스로 돌린다 —
주기적으로 "최근 구간 vs 직전 베이스라인 구간"을 비교해 회귀를 자동 감지하고 웹훅으로 알린다.
(Datadog Query Regression Detection의 축소판 — 베이스라인은 직전 구간 하나로 단순화)

감지 규칙(쿼리별 쿨다운으로 중복 알림 방지):
- 신규 쿼리 유입 (base에 없던 쿼리)
- 호출량 급증 (QPS +200% 이상)
- 레이턴시 회귀 (평균 +200% 이상)
- 읽는 행수 폭증 (rows/call +500% 이상 — 플랜 변화·IN절 폭증 대리 신호)

E2E 검증(감지 구간 최근 1분 vs 직전 2분으로 단축):
1. 점조회 베이스라인 2,249회
2. 신규 LIKE 풀스캔 + 점조회 급증 주입
3. 감지 폴러가 자동으로 잡아 Discord로 발송

```
INFO RegressionDetector: 회귀 감지 알림 instance=local-mysql findings=2
웹훅 발송 실패: 0건
Discord webhook: HTTP 204
```

웹훅 어댑터도 이기종 대응 — URL에 discord.com이 있으면 {"content":...},
아니면 Slack 포맷 {"text":...}로 자동 전환. URL은 비밀값이라 환경변수(DBTOWER_WEBHOOK_URL)로만 주입.

AI 1차 분석(확장3): 감지 결과를 Anthropic Messages API(Java SDK)로 1차 분석해 알림에 첨부.
판단을 통째로 맡기지 않고 docs/ai-analysis-rules.md의 기종별 판단 기준을 system 프롬프트로 넣어
같은 입력에 일관된 판정이 나오게 한다(레퍼런스와 같은 접근). ANTHROPIC_API_KEY 미설정이면
조용히 비활성화되고 규칙 기반 알림만 발송 — 분석 실패가 알림 자체를 막지 않는다.

## 16. 확장4 — 웹 UI (시점 비교 + EXPLAIN + AI 분석 화면)

REST API 위에 의존성 없는 정적 SPA를 올렸다 (Spring Boot가 함께 서빙 — java -jar 하나로 화면까지).
화면 구도는 DB Insight류 레퍼런스를 참고: 그래프에서 구간을 드래그로 고르고,
Top Query 증감과 신규 쿼리를 보고, 클릭 한 번으로 실행계획과 분석까지 내려간다.

E2E 시나리오 (실측 2026-07-03):
1. 베이스라인 부하 3분(점조회) -> 급증 부하 2분(점조회 x3 + 신규 LIKE 풀스캔 COUNT). 대상: sample.users 8,000행
2. 비교 구간 20:54:30~20:57:05 vs 조회 구간 20:57:05~20:59:30 으로 비교 조회

결과 (스크린샷 docs/images/webui/):

- 01-dashboard.png — 이기종 4대(MySQL/PG/MSSQL/dbtower-self) 헬스·버전 카드,
  활동 그래프(QPS)에 부하 스파이크가 그대로 보임. 그래프는 스냅샷 누적 카운터의
  인접 배치 차분 — 시점 비교와 같은 원리의 다른 뷰라 추가 수집이 없다.
```
GET /api/instances/1/activity?from=...&to=...
[{"time":"...15:18:00","qps":0.48,"avgLatencyMs":0.94}, ...]
```
- 02-compare.png — 비교 조회. 요약 스트립 "호출량 +461% / 평균 레이턴시 +69% /
  읽은 행수 +852% / 신규 쿼리 1개". 신규 LIKE 쿼리에 NEW 뱃지 + 행 하이라이트,
  기존 점조회는 QPS 2.4 -> 8.6 (▲ 6.2) 증감 표시.
```
GET /api/instances/1/compare?... -> newQueryCount: 1, totalCallsChangePct: 460.88
```
- 03-explain.png — NEW 쿼리 클릭 -> 정규화 텍스트의 파라미터(?)를 실제 값으로 고쳐
  실행계획 실행 -> access_type=ALL(풀스캔), rows_examined_per_scan=8118 확인,
  규칙 기반 지적("테이블 풀스캔 — 인덱스가 없거나 타지 못하는 조건") 자동 표시.
  즉 "비교로 범인 지목 -> EXPLAIN으로 원인 확인"이 화면 안에서 완결된다.
- 04-ai.png — AI 1차 분석 버튼. ANTHROPIC_API_KEY 미설정 상태라
  "AI 분석 비활성화 — 규칙 기반 지적까지만 표시" 폴백 메시지 확인
  (회귀 알림과 동일한 판단 기준 프롬프트를 쓰는 POST /ai-analysis 엔드포인트).
- 05-slow.png — Slow Query 탭 (기종별 slowQueries 통합 뷰).

검증 방법: 헤드리스 크롬을 CDP(Chrome DevTools Protocol)로 조작해 실제 클릭/입력을
재현하고 캡처했다. 전 과정에서 JS 콘솔 에러 0건.

밟은 함정: hidden 속성은 UA 스타일 display:none으로 동작하는데, 요약 스트립의
display:flex가 이를 덮어써 빈 박스가 보였다. `.summary-strip[hidden]{display:none}`으로 해소 —
"hidden 속성은 display를 지정한 요소에서는 무력화된다"는 표준 동작.

## 17. 확장5 — MCP 서버: AI 에이전트가 DBTower를 도구로 쓴다

웹 UI(확장4)가 사람의 채널이라면 MCP(Model Context Protocol)는 AI 에이전트의 채널이다.
회귀 감지(확장3)가 push(플랫폼이 사람에게 민다)라면 MCP는 pull(에이전트가 필요할 때 당겨쓴다) —
같은 코어를 채널만 바꿔 노출한다. 레퍼런스가 시점비교·실행계획 등을 MCP로 제공해
Slack의 AI가 DB 알럿을 스스로 분석하게 한 것과 같은 방향.

구현: SDK 없이 JSON-RPC 2.0을 직접 구현했다 (io.dbtower.mcp.McpStdioServer, 의존성은
클래스패스에 이미 있는 Jackson뿐). MCP stdio는 "한 줄 = JSON-RPC 메시지" 프레이밍이고
서버가 알아야 할 메서드는 initialize / notifications/initialized / tools/list / tools/call 네 개다.
도구 실행은 전부 기존 REST API 위임 — MCP 계층에 비즈니스 로직이 없어서 채널이 늘어도
검증·보안은 코어 한 곳에서 끝난다.

노출 도구 8종: list_instances, health, query_stats, slow_queries, compare, activity,
explain, replication. stdout은 JSON-RPC 전용이므로 gradle을 거치지 않고 java -cp로 직접
실행한다 (scripts/dbtower-mcp.sh + ./gradlew writeMcpClasspath).

E2E 실측 (JSON-RPC를 stdio로 직접 주입):

```
initialize      -> {"name":"dbtower","version":"0.1.0"}
tools/list      -> [list_instances, health, query_stats, slow_queries,
                    compare, activity, explain, replication]
tools/call list_instances -> 이기종 4대 목록 (isError:false)
tools/call compare(부하 구간)  -> newQueryCount: 1, totalCallsChangePct: 460.88
tools/call explain(LIKE 풀스캔) -> findings: ["테이블 풀스캔(access_type=ALL) — ..."]
tools/call health(instanceId=999) -> isError:true "DBTower API 400: 등록되지 않은 인스턴스: 999"
```

즉 웹 UI에서 사람이 하던 "비교로 범인 지목 -> EXPLAIN으로 원인 확인" 흐름을
AI 에이전트가 도구 호출로 그대로 수행할 수 있다.

Claude Code 등록 방법:
```
./gradlew writeMcpClasspath
claude mcp add dbtower -- sh /path/to/dbtower/scripts/dbtower-mcp.sh
```

### 17-1. MCP HTTP 전송 + 웹 UI 연동 카드

stdio에 더해 Streamable HTTP 전송(POST /mcp)을 앱에 내장했다 — 앱이 떠 있으면 MCP 연동
준비가 끝나고, 별도 프로세스·클래스패스 준비가 필요 없다. 프로토콜 코어(McpProtocolHandler)는
stdio와 공유 — 전송만 다르고 도구·검증은 같다.

```
POST /mcp initialize                 -> {"serverInfo":{"name":"dbtower","version":"0.1.0"}}
POST /mcp notifications/initialized  -> 202 Accepted (알림 — 본문 없음)
POST /mcp tools/list                 -> 도구 8종
POST /mcp tools/call compare(부하구간) -> newQueryCount: 1, totalCallsChangePct: 460.88
```

웹 UI Monitoring 탭에 MCP 연동 카드 추가 (docs/images/webui/06-mcp.png):
- 등록 명령 원클릭 복사 — `claude mcp add --transport http dbtower http://localhost:8080/mcp`
- 제공 도구 8종 카드 — 하드코딩이 아니라 화면이 직접 POST /mcp tools/list를 호출해 그린다.
  이 목록이 보인다는 것 자체가 "MCP 엔드포인트가 살아 있다"의 증거.

### 16-1. AI 1차 분석 실측 — claude CLI 백엔드 (API 키 없이)

AiAnalyzer에 백엔드 자동 선택을 추가했다:
- ANTHROPIC_API_KEY 있음 -> Anthropic Java SDK (운영 구성)
- 키 없음 + claude CLI 설치됨 -> headless 모드(claude -p)로 호출 — 로컬 개발은 Claude 구독으로 동작
- 둘 다 없음 -> 조용히 비활성화

CLI 호출 설계:
- 프롬프트는 argv가 아니라 stdin으로 전달 — SQL·실행계획에 어떤 문자가 와도 인자 파싱과 무관
- --setting-sources "" 로 사용자/프로젝트 설정을 배제 — 어떤 로컬 환경에서도 같은 형식의
  순수 분석 텍스트가 나온다 (처음엔 사용자의 출력 스타일 설정이 응답에 섞여 나왔다 — 실측으로 발견)
- 판단 기준 문서는 --append-system-prompt 로 주입 (API 백엔드와 동일한 프롬프트)

실측 (04-ai.png):
LIKE '%user1%' 풀스캔 쿼리(8,118행)에 대해 — access_type=ALL의 원인을 앞 와일드카드로 특정하고
(B+Tree 시작점 원리 인용), filtered=11.11을 문서 기준으로 해석하고, 접두사 전환/FULLTEXT/
검색 엔진 전환까지 판단 기준 문서 안에서만 제안했다. 문서 밖 수치에 대해서는
"주어진 계획만으로 판단할 수 없다"고 답해 — "근거 없으면 모른다고 말하라"는 프롬프트 규칙이
실제로 지켜지는 것을 확인했다.

## 18. 확장6 — MongoDB·Oracle 추가: "새 기종 = 구현체 1개"의 실측 검증

이번 확장의 목적은 기능 추가가 아니라 **아키텍처 주장 검증**이다.
"새 기종 지원 = Operator 구현체 1개"라고 문서에 적어 왔으니, 실제로 두 기종을 추가하면서
플랫폼 코드(스냅샷 폴러·시점 비교·회귀 감지·웹 UI·MCP)가 몇 줄 바뀌는지 세어 본다.

### 18-1. 바뀐 것과 안 바뀐 것

새로 만든 파일: `OracleOperator`(JDBC 계열), `MongoOperator`(비 JDBC), `MongoClientCache`,
`BackupCommands`(백업 실행 유틸 — JDBC 골격에서 분리).
기존 코드 수정: enum 값 2개, 팩토리 case 2줄, 규칙 분석기 case 2블록, CSS 색 2줄. 끝.
스냅샷 폴러·시점 비교·회귀 감지·웹 UI·MCP 핸들러는 **0줄 수정**으로 5기종을 처리한다.

MongoDB가 증명 포인트다 — SQL도 JDBC도 없는 기종이 인터페이스 뒤로 들어온다:
- 통계 소스: system.profile (프로파일러 레벨 2), queryHash 단위 집계
- explain 입력: SQL 대신 명령 JSON `{"find": "users", "filter": {...}}`
- 읽기 명령 allowlist(find/aggregate/count/distinct)가 requireSelect와 같은 원칙을 담당

### 18-2. 실측 — 등록부터 백업까지 (2026-07-03)

```
POST /api/instances {"type":"MONGODB", ...}  -> {"id":7,"name":"local-mongo"}   (등록 시 접속 검증 통과)
POST /api/instances {"type":"ORACLE", ...}   -> {"id":8,"name":"local-oracle"}

GET /api/instances/7/health -> {"up":true,"version":"MongoDB 7.0.37","pingMillis":3}
GET /api/instances/8/health -> {"up":true,"version":"Oracle AI Database 26ai Free Release 23.26.2.0.0 ...","pingMillis":3}

GET /api/instances/7/query-stats — regex 검색이 그대로 잡힌다:
  {"find": "users", "filter": {"name": {"$regex": "user1123"}}}
  calls: 5, rowsExamined: 100000   <- 호출당 20,000 문서 전체를 훑는 COLLSCAN의 수치 증거

POST /api/instances/7/explain {"find":"users","filter":{"name":{"$regex":"user123"}}}
  -> findings: ["컬렉션 풀스캔(COLLSCAN) — 필터 조건을 받는 인덱스가 없습니다"]
POST /api/instances/7/explain {"delete": ...}
  -> 400 "explain은 읽기 명령만 허용합니다: [find, count, aggregate, distinct]"

POST /api/instances/8/explain "SELECT * FROM users WHERE name LIKE '%user123%' ORDER BY price"
  -> findings: [테이블 풀스캔(TABLE ACCESS FULL), 정렬 연산(SORT ORDER BY)]
  -> 플랜 원문: |* 2 | TABLE ACCESS FULL| USERS | 1000 | ...
POST /api/instances/8/explain "DELETE FROM users" -> 400 "EXPLAIN은 SELECT 쿼리만 허용합니다"

POST /api/instances/7/backup -> SUCCESS ./backups/mongo-local-mongo-....archive (1,329,396 bytes, 214ms)
POST /api/instances/8/backup -> SUCCESS (server) DATA_PUMP_DIR/oracle-local-oracle-....dmp (32.0s)

GET /api/instances/7/replication -> {"role":"STANDALONE","detail":"레플리카셋 미구성"}
GET /api/instances/8/replication -> {"role":"PRIMARY","detail":"open_mode=READ WRITE protection=MAXIMUM PERFORMANCE"}

MCP (코드 수정 0줄):
tools/call list_instances -> 6개 (MONGODB·ORACLE 포함)
tools/call health {"instanceId":7} -> MongoDB 7.0.37
tools/call explain {"instanceId":8, ...} -> Oracle 플랜 텍스트
```

백업 실행 모델이 4가지로 갈라진 것도 기록해 둔다 — 같은 "FULL 백업" 정책이:
- MySQL: 외부 CLI + 비밀번호는 env(MYSQL_PWD)
- PostgreSQL: 외부 CLI + env(PGPASSWORD)
- MongoDB: 외부 CLI + 비밀번호를 --config /dev/stdin으로 stdin 전달 (비밀번호 env가 없는 도구)
- SQL Server / Oracle: 서버 사이드 실행 (BACKUP DATABASE SQL / DBMS_DATAPUMP PL/SQL API)
"비밀번호를 argv에 싣지 않는다"는 원칙은 4가지 모델 전부에서 유지된다.

시점 비교도 비 JDBC 기종에서 코드 수정 없이 동작한다 (부하 실측, 2026-07-04 00:03~00:07):
```
GET /api/instances/7/activity  -> QPS 0 -> 10.67 급증이 그래프 시계열에 표시
GET /api/instances/7/compare (조용한 구간 vs 부하 구간)
  -> target: totalCalls 864, totalRowsExamined 9,036,411, newQueryCount: 2
     (regex find + countDocuments 둘 다 base에 없던 신규 쿼리로 NEW 표시)
  -> changePct는 null — base가 0일 때 증감률 대신 null을 주는 설계가 그대로 적용됨
```

### 18-3. 실측으로 발견한 함정 — Hibernate CHECK 제약은 enum 확장을 모른다

등록 첫 시도가 500으로 실패했다:
```
ERROR: new row for relation "database_instance" violates check constraint "database_instance_type_check"
```
원인: 메타데이터 DB(PostgreSQL)의 database_instance 테이블에 Hibernate가 처음 생성해 둔
CHECK 제약이 3기종 시절 값 목록이었다. ddl-auto=update는 컬럼 추가는 해도
**기존 CHECK 제약은 갱신하지 않는다**. 수동으로 제약을 5기종 값으로 재생성해 해결:
```sql
ALTER TABLE database_instance DROP CONSTRAINT database_instance_type_check;
ALTER TABLE database_instance ADD CONSTRAINT database_instance_type_check
  CHECK (type IN ('MYSQL','POSTGRESQL','MSSQL','MONGODB','ORACLE'));
```
운영 교훈: enum을 DDL 제약으로 내리는 순간, enum 확장은 코드 배포가 아니라 스키마 마이그레이션이 된다.
Flyway 같은 마이그레이션 도구가 필요한 이유를 이 프로젝트 안에서 직접 만났다.

## 19. Phase A1 — 인증·인가: "누구나 들어올 수 있는 관제탑"이라는 한계를 닫다

### 19-1. 한계의 인지

확장6까지의 DBTower에는 인증이 없었다. 콘솔·REST·MCP 전부 열려 있어서,
네트워크에 접근 가능한 누구든 인스턴스를 등록·삭제하고 백업을 실행할 수 있었다 —
DB 접속정보를 다루는 관리 도구로서 가장 큰 결격 사유였고, 같은 장르의 Percona PMM이
서비스 계정 + 접근 제어를 갖추는 이유이기도 하다. 그래서 로드맵 Phase A의 1번으로 올렸다.

### 19-2. 설계 — 주체가 둘이라 인증도 둘이다

| 주체 | 인증 | CSRF |
|---|---|---|
| 사람 (웹 콘솔) | 세션 폼 로그인, 사용자는 메타 DB에 BCrypt 저장 | 쿠키(XSRF-TOKEN) -> SPA가 헤더로 반환하는 표준 패턴 |
| 기계 (MCP 클라이언트·자동화) | Bearer 서비스 토큰 (상수 시간 비교) | 대상 아님 — 쿠키 세션이 없어 CSRF가 성립하지 않는다 |

인가는 역할 2개: 진단(조회·explain)은 VIEWER부터, 대상 DB를 바꾸는 행위
(등록/삭제/백업/정책)와 서비스 토큰 조회는 ADMIN만.

fail-closed 원칙 두 가지:
- admin 초기 비밀번호를 하드코딩(admin/admin)하지 않는다 — 미설정 시 랜덤 생성 + 로그 1회 안내(Jenkins 방식)
- 서비스 토큰 미설정 시 "무인증"이 아니라 "기동마다 랜덤 토큰" — 모르면 아무도 못 쓴다

### 19-3. 실측 (2026-07-04)

```
미인증:  GET /            -> 302 /login.html   (브라우저는 로그인으로)
         GET /api/...     -> 401               (API는 상태코드로 — SPA가 구분 처리)
         POST /mcp        -> 401

viewer:  GET /api/instances                    -> 200
         POST /api/instances/8/explain         -> 200  (진단은 VIEWER부터)
         POST /api/instances (등록)             -> 403
         POST /api/instances/8/backup          -> 403
         GET /api/security/mcp-token           -> 403

admin:   GET /api/security/mcp-token           -> 200  (MCP 카드가 이걸로 등록 명령을 완성)

기계:    Bearer 올바른 토큰  GET /api/...        -> 200 (ADMIN 권한)
         Bearer 틀린 토큰                        -> 401
         POST /mcp tools/call health(mongo)     -> 정상 (CSRF 없이 — 쿠키가 없으므로)
```

MCP 등록 명령은 토큰 포함으로 바뀐다:
`claude mcp add --transport http dbtower http://localhost:8080/mcp --header "Authorization: Bearer <토큰>"`
stdio 방식은 별도 프로세스라 DBTOWER_API_TOKEN 환경변수를 앱과 동일하게 준다.

### 19-4. 밟은 함정 둘 (Spring Boot 4 / Security 7)

- **AntPathRequestMatcher가 사라졌다.** Security 7에서 제거 — 람다 RequestMatcher로 대체했다.
- **로그인하면 CSRF 토큰이 회전한다.** 세션 고정 방어로 인증 성공 시 기존 CSRF 토큰이
  무효화된다. curl로 E2E를 짤 때 로그인 전 토큰을 재사용해 403을 맞고 확인했다 —
  브라우저 SPA는 매 요청 쿠키를 다시 읽으므로 자연히 무관하다.
- (부수) Boot 4 모듈화로 @AutoConfigureMockMvc 패키지가 이동했고, spring-security-test와
  MockMvc의 연결(@WithMockUser)도 spring-boot-starter-security-test라는 전용 스타터가 맡는다.

보안 테스트 10건(SecurityConfigTest)으로 인가 표를 코드로 고정 — 총 41건 전부 통과, CI 그린.

## 20. Spring Modulith — "경계는 문서가 아니라 빌드가 지킨다"

### 20-1. 왜

"기종 분기는 팩토리 한 곳", "플랫폼 코드는 인터페이스만 안다"는 주장을 계속 문서에 써 왔다.
그런데 문서 속 아키텍처는 강제력이 없다 — 커밋 하나가 조용히 무너뜨려도 아무도 모른다.
Spring Modulith를 도입해 패키지 = 모듈로 선언하고, 모듈 간 순환 의존을 테스트가
빌드에서 실패시키게 했다 (ModularityTests, CI에 포함).

### 20-2. 도입하자마자 순환 2개를 잡혔다

verify() 첫 실행이 바로 실패했다 — 좋은 실패다. 문서로는 깨끗하다고 믿었던 구조에
순환이 실재했다는 뜻이니까.

**registry <-> operator**: RegistryService가 등록 검증·풀 정리를 위해
DbmsOperatorFactory/ConnectionPools를 직접 사용 -> registry가 operator에 의존,
동시에 모든 Operator가 registry의 DatabaseInstance를 사용 -> 순환.
해소: 의존 역전 — registry가 필요로 하는 최소 능력을 registry 소유 인터페이스
(InstanceOperations: health/release)로 선언하고 operator가 구현. HealthStatus 레코드도
"연결 상태"라는 의미상 registry로 이동. 방향은 operator -> registry 한쪽만 남았다.

**insight <-> alert**: InsightController가 alert.AiAnalyzer를 쓰고, alert.RegressionDetector가
insight.ComparisonService를 쓰는 순환. 해소: AiAnalyzer는 애초에 "판정"이므로
analysis 모듈로 이동 — insight/alert 둘 다 analysis를 바라보는 한 방향 구조가 됐다.

### 20-3. 결과 (2026-07-04 실측)

- 모듈 8개 인식: registry / operator / insight / alert / analysis / backup / mcp / security
- ModularityTests 2건 통과 (경계 검증 + C4 다이어그램 생성 -> docs/modules/)
- 전체 테스트 43건 통과, 리팩터 후 스모크: 5기종 health 정상,
  죽은 인스턴스 등록 시 DIP 경유 검증이 "접속 실패로 등록 거부" 응답 — 동작 동일
- 이제 누군가 registry에서 operator를 import하는 순간 CI가 빨간불이 된다

## 21. Phase A2 — 인스턴스 비밀번호 암호화: 평문 저장이라는 한계를 닫다

한계: 등록된 인스턴스의 비밀번호가 메타 DB에 평문으로 저장됐다 — 메타 DB가 유출되면
관리 대상 DB 전체의 열쇠가 함께 유출되는 구조.

설계:
- AES-256-GCM (랜덤 IV 12바이트를 암호문 앞에 결합, 태그 128비트), 키는 DBTOWER_ENCRYPTION_KEY(base64 32바이트)
- JPA AttributeConverter + `enc:v1:` 접두사 디스패치 — 기존 평문 행은 마이그레이션 없이 읽히고
  다음 저장 때 자연 재암호화. v1은 향후 키·알고리즘 교체 대비
- 키 정책의 비대칭: API 토큰은 미설정 시 랜덤 생성(fail-closed)이지만, 암호화 키는 랜덤 생성이
  오히려 위험하다 — 재기동하면 기존 암호문을 영영 못 푼다. 그래서 미설정=WARN+평문(과도기),
  잘못된 키=기동 거부, 키 없이 암호문 조우=예외. "조용히 평문으로 새는" 경로는 없다

실측 (2026-07-04):
```
기존 평문 행:   id 1(MySQL)·7(Mongo)·8(Oracle) health 전부 up — 하위 호환 확인
신규 등록:      raw 컬럼 = enc:v1:PflkG...  (psql로 직접 확인)
복호화 경로:    암호문 저장 상태에서 health up (MongoDB 7.0.37)
테스트:         SecretCipher 8건(변조 시 복호 실패 포함) + Converter 6건 + JPA 통합 2건
```

## 22. Phase A3 — Flyway: 18-3절에서 밟은 함정의 정식 해결

한계(18-3절에서 실측): ddl-auto=update는 기존 CHECK 제약을 갱신하지 않아 기종 추가가
수동 ALTER가 됐다. 스키마의 단일 권위를 마이그레이션으로 이관한다.

- V1__baseline.sql: 테이블 5개(database_instance 5기종 CHECK, query_snapshot 복합 인덱스,
  backup_policy_entity, backup_run, platform_user) — 엔티티 소스 + 라이브 스키마 \d 대조로 작성
- ddl-auto update -> validate: 이제 엔티티-스키마 불일치는 조용한 드리프트가 아니라 부팅 실패다
- 기존 DB는 baseline-on-migrate로 비파괴 도입 — instances 6·snapshots 46,711 무손상 실측,
  빈 스크래치 DB에서는 V1이 실제 실행되어 기동까지 검증

**Boot 4 함정 (실측으로 발견)**: flyway-database-postgresql 의존성만 넣으면 부팅은 되는데
Flyway가 조용히 실행되지 않는다 — 로그 0건, history 테이블 없음. Boot 4의 자동구성
모듈화로 spring-boot-starter-flyway가 따로 필요하다. "조용한 미실행"이 가장 나쁜 실패
양식이라는 점에서 digest 절단(10절)과 같은 계열의 함정.

## 23. Phase A4 — 스냅샷 보존 정책: 무한 적재를 닫다

한계: 60초 주기 스냅샷이 무한 적재됐다 (이 시점 실측 50,960행/이틀 — 방치하면 메타 DB가
플랫폼의 병목이 된다). 선례: AWS Performance Insights 기본 보존 7일.

- SnapshotRetentionJob: 1시간 주기, cutoff(기본 7일) 이전을 JPQL 벌크 DELETE 한 문장으로 —
  대상 행을 영속성 컨텍스트에 올리지 않는다 (수십만 행 로딩 금지)
- @Modifying(clearAutomatically=true)로 벌크 우회에 따른 1차 캐시 불일치 차단
- retention-days <= 0 = 보존 무제한 스위치
- H2 통합 테스트: cutoff 이전 2행만 삭제, 이후 2행 보존 — 경계 정확성 고정

## 24. 병렬 개발 방식 기록 — 아크 3개를 worktree로 동시에

A2·A3·A4는 서로 다른 모듈(security/설정/insight)이라 병렬 가능했다. 각 아크를
git worktree + 브랜치(arc/*)로 격리해 동시에 구현하고, 순차 병합 후 통합 검증했다:
- 병합 순서 A4 -> A2 -> A3 (스키마 권위를 바꾸는 Flyway를 마지막에)
- application.yml은 셋 다 수정했지만 서로 다른 블록이라 자동 병합
- 병합마다 전체 테스트, 최종 통합 상태에서 라이브 E2E (21~23절의 실측이 그 결과)
- 테스트 65건 전부 통과 (31 -> 43 -> 65: 보안 10 + 모듈 2 + 암호화 16 + 보존 6)

## 25. Phase A6 — 감사 로그: "누가 언제 무엇을 했나"의 공백을 닫다

한계: A1로 인증은 생겼지만, 인증된 사용자가 무엇을 했는지 기록이 없었다.
관리 도구는 접근 통제만큼 사후 추적이 기본 요건이다.

설계:
- audit 모듈 신설 — /api/** 의 POST/PUT/DELETE(상태 변경·explain·백업)와 로그인 성공/실패 기록. GET 조회는 폴링 노이즈라 제외
- 인터셉터(인가 통과 요청) + AuthorizationDeniedEvent 리스너(403 거부) 조합 — 인가 거부는
  DispatcherServlet 앞에서 끝나 인터셉터가 못 보므로 리스너로 사각지대를 메웠다
- 감사 저장 실패는 삼켜서 본 요청을 지키고, GET /api/audit는 ADMIN만
- 새 테이블은 Flyway V2__audit_event.sql — ddl-auto=validate 체제라 마이그레이션이 유일한 경로

실측 (2026-07-04, V2 마이그레이션이 실 DB에 적용됨을 로그로 확인):
```
POST /api/instances/8/explain 실행 후 GET /api/audit ->
  2026-07-04T15:10:40  api-token  ADMIN  POST /api/instances/8/explain -> 200 (145ms)
```
Security 7.1 실측 함정: InteractiveAuthenticationSuccessEvent가 더 이상
AuthenticationSuccessEvent 하위 타입이 아니고, AuthorizationFilter가 이벤트에 싣는 객체가
RequestAuthorizationContext가 아니라 HttpServletRequest였다 (첫 테스트 실패로 발견·수정).

## 26. Phase B1 — Wait Event 분석: "무엇을 기다렸나"

load%가 "어떤 쿼리가 시간을 쓰나"를 답한다면, Wait Event는 "그 시간에 무엇을 기다렸나"
(CPU/IO/Lock/Latch)를 답한다 — DBA가 장애 시 가장 먼저 보는 축. DbmsOperator에 메서드
1개(waitEvents) 추가로 5기종 통합, "새 능력 = 인터페이스 메서드 1개"를 또 한 번 실측.

기종별 소스와 의미론(중요 — 같은 "대기"가 아니다):

| 기종 | 소스 | 의미 |
|---|---|---|
| MySQL | events_waits_summary_global_by_event_name | 기동 이후 누적(피코초->ms) |
| PostgreSQL | pg_stat_activity (active 세션) | 현재 순간 스냅샷 — 무부하면 빈 배열 |
| SQL Server | dm_os_wait_stats | 기동 이후 누적, idle/백그라운드 필터 |
| Oracle | v$system_event (Idle 제외) | 기동 이후 누적(마이크로초->ms), wait_class |
| MongoDB | serverStatus 대기 큐 + WT 티켓 | 현재 게이지 — wait event가 아니라 대기 지표 |

실측 (2026-07-04, 라이브):
```
MySQL:  wait/io/table/sql/handler io count=17,336,000 totalMs=3705.36
        + (안내) 비활성 wait instrument 349종은 집계에 없음 — 설정을 바꾸지 않고 정직하게 표기
PG:     무부하 구간이라 [] — 스냅샷 방식의 정직한 결과
Oracle: db file sequential read User I/O 24,948회, latch: shared pool Concurrency 3,093회
Mongo:  globalLock.currentQueue.readers QUEUE count=0, WT concurrentTransactions TICKET
MCP:    도구 9종(wait_events 포함), 웹 콘솔 Monitoring 탭에 카드 렌더링
```

두 가지 정직성 설계가 핵심이다: (1) MySQL은 관제 도구가 대상 설정을 바꾸면 안 되므로
비활성 instrument를 켜지 않고 "안 보이는 범위"를 응답에 명시, (2) MSSQL idle 필터는
1차 응답에서 SOS_WORK_DISPATCHER가 8억 ms로 도배되는 것을 보고 실측 기반으로 보강.

![Wait Events — MySQL 대기 이벤트와 비활성 instrument 안내](images/webui/08-wait.png)

## 27. Phase A8 — 대상 DB 최소 권한 계정: 실측으로 확정한 권한 목록

한계: 지금까지 root/sa/system급 계정으로 대상 DB에 붙었다. 관제 도구는 조회에 필요한
최소 권한만 가진 전용 계정으로 붙어야 한다(Datadog DBM 등의 관행). 문제는 "최소가 얼마냐"를
추측하면 틀린다는 것 — 그래서 권한 0에서 시작해 에러 원문을 수집하며 하나씩 더했다.

확정된 최소 집합 (docs/least-privilege.md, 전부 실측):
- MySQL: sample.* + performance_schema digest + mysql.slow_log SELECT + REPLICATION CLIENT/SLAVE
- PostgreSQL: LOGIN + pg_read_all_stats (pg_monitor 전체는 과함)
- SQL Server: VIEW SERVER PERFORMANCE STATE 한 줄 (2022 세분화 권한)
- Oracle: CREATE SESSION + SELECT_CATALOG_ROLE + 대상 테이블 READ
- MongoDB: read@sample + clusterMonitor@admin

실측으로 드러난 의외의 지점:
- MongoDB read 롤은 system.profile을 못 읽는다(Unauthorized) — 그런데 clusterMonitor가
  그 권한을 이미 갖고 있어 커스텀 롤이 불필요했다(showPrivileges로 근거 확보)
- PostgreSQL은 권한이 없어도 query-stats가 HTTP 200으로 성공하되 전 행이
  <insufficient privilege>로 조용히 저하된다 — 에러가 아니라 빈 데이터라 더 위험한 형태
- 백업은 관리 작업이라 이 계정 범위 밖 — ADMIN 권한 계정을 별도로 둔다는 경계도 문서화

## 28. Phase A5 — HA 안전: 스케줄러 분산 락 (ShedLock)

한계: @Scheduled 폴러 4종(통계 수집·회귀 감지·보존 삭제·백업)이 단일 프로세스 전제였다.
앱을 2개 이상 띄우면 모든 노드가 동시에 같은 대상 DB를 수집하고, 회귀 감지 쿨다운이
인메모리라 노드마다 따로 놀아 같은 회귀를 중복 알림한다.

설계:
- ShedLock(JdbcTemplate 프로바이더) — 폴러마다 고정 이름 락, 한 시점에 한 노드만 실행
- LockProvider 빈은 루트 패키지(공유 인프라)에 둬 Modulith 순환 회피, usingDbTime으로
  노드 JVM 클럭이 아니라 메타 DB 시계로 만료 판정(HA 클럭 스큐 방어)
- lockAtLeastFor를 주기 근처로 둬 드리프트하는 2번째 노드의 중복 실행 차단,
  lockAtMostFor는 크래시 복구 상한(실제 실행시간보다 넉넉히)
- V3__shedlock.sql (공식 PostgreSQL 스키마)

정직한 잔여 한계: RegressionDetector 쿨다운 Map은 노드별 인메모리로 남겼다. 락 stickiness로
정상 시엔 한 노드가 계속 이겨 중복이 급감하지만, 락 핸드오프(failover/재기동) 직후엔
새 승자의 Map이 비어 이미 보낸 회귀를 1회 재알림할 수 있다. 완전 해결은 쿨다운을 메타 DB로
외부화하는 것(추가 마이그레이션)이며, 잔여 리스크를 "중복 1회"로 판단해 수용했다.

실측:
```
2노드(18081+18082, 공유 메타DB, 15초 주기, 약 90초):
  NODE A: 스냅샷 수집 완료 = 54건 / NODE B: = 0건
  NODE B: "Not executing 'snapshot-collect'. It's locked." (매 틱)
8080 단일 노드: shedlock 테이블에 4종 락 레코드 확인(진행 중 lock_until > now)
```

## 29. Phase A7 — 백업 복원 검증: "테스트 안 한 백업은 백업이 아니다"

한계: 지금까지 "백업했다"까지만 있고 "그 백업이 복원 가능한가"는 검증하지 않았다
(3-2-1-1-0의 "0 errors"가 요구하는 지점).

설계 원칙: 기종마다 검증 가능한 수준이 다르다는 현실을 그대로 반영하고, 못 하는 것을
통과로 위장하지 않는다. 3값 상태 RestoreVerification(VERIFIED/FAILED/UNSUPPORTED).
복원은 반드시 격리된 임시 대상(dbtower_verify_<타임스탬프>)에만 — 원본은 절대 안 건드린다.

| 기종 | 수준 | 방법 |
|---|---|---|
| MySQL | VERIFIED | .sql 덤프를 임시 DB에 실제 복원(CREATE DATABASE/USE 제거로 원본 격리), 테이블 수 확인 후 drop |
| PostgreSQL | VERIFIED | pg_dump를 임시 DB에 psql -v ON_ERROR_STOP=1로 복원, public 테이블 수, drop FORCE |
| MongoDB | VERIFIED | 아카이브를 임시 DB로 mongorestore(ns 리맵), 컬렉션 수, drop + 임시 아카이브 제거 |
| SQL Server | VERIFIED(VERIFYONLY) | 서버 측 .bak라 파일 접근 불가 — RESTORE VERIFYONLY로 백업셋 무결성만(전체 복원 아님) |
| Oracle | UNSUPPORTED | Data Pump 서버 측 산출물 — IMPDP+정리 필요, 자동 검증 범위 밖. 통과로 위장 안 함 |

실측 (2026-07-04, 라이브):
```
PostgreSQL: VERIFIED — 임시 DB로 덤프 복원 성공, 복원 테이블 3개, 임시 DB 정리 확인
Oracle:     UNSUPPORTED — "서버 측 산출물이라 IMPDP 필요, 범위 밖" (정직 표기)
임시 검증 DB 잔여물: 0 (정리 확인), 원본 sample DB 불변
FAILED 분기 증명: 없는 덤프 verify -> FAILED (러버스탬프 아님)
verify_status를 backup_run에 저장 (V4__backup_run_verify.sql)
```

## 30. Phase A 완결 (A1~A8)

인증 없는 관제탑에서 시작해 운영 안전 8개 축을 전부 닫았다. 각 절은 "한계 인지 -> 개선 ->
실측 -> 잔여 한계 정직 명시" 아크로 기록됐다:

| # | 한계였던 것 | 개선 | 절 |
|---|---|---|---|
| A1 | 누구나 들어오는 관제탑 | 세션+토큰 인증, 역할 분리 | 19 |
| A2 | 비밀번호 평문 저장 | AES-256-GCM + 접두사 하위호환 | 21 |
| A3 | ddl-auto=update 스키마 드리프트 | Flyway baseline + validate | 22 |
| A4 | 스냅샷 무한 적재 | 보존 7일 벌크 삭제 | 23 |
| A6 | 누가 뭘 했는지 모름 | 감사 로그 | 25 |
| A8 | root/sa급 접속 | 최소 권한 계정 실측 확정 | 27 |
| A5 | 단일 프로세스 전제 | ShedLock 분산 락 | 28 |
| A7 | 복원 안 되는 백업 | 복원 검증 3값 | 29 |

테스트 81건, 마이그레이션 V1~V4, 이 8개 아크의 상당수를 git worktree 병렬 개발로 진행(24절).

## 31. 감사 로그 검색 — Specification의 제자리 (동적 필터)

앞서 리팩터 검토에서 "정적 쿼리를 커스텀 프래그먼트로 감싸는 건 과설계"라고 판단했다.
그 판단의 짝이 이것이다 — Specification(또는 프래그먼트)이 정말 값을 하는 건 **필터가
런타임에 조립되는 동적 쿼리**일 때다. 감사 로그 검색이 정확히 그 경우다.

한계: 감사 로그가 쌓이기 시작하니 "누가 무엇을 했나"를 좁혀 볼 방법이 필요해졌다.
필터는 사용자·action·인스턴스·결과코드·기간 6종이고, 어느 것이든 있을 수도 없을 수도 있다.
이걸 파생 메서드나 @Query로 풀면 조합 수만큼 메서드가 폭발한다(findByPrincipal,
findByPrincipalAndOutcome, findByOutcomeAndInstanceIdBetween... 2^6).

설계:
- AuditEventRepository가 JpaSpecificationExecutor를 함께 상속
- AuditSpecifications의 각 빌더는 파라미터가 비면 null 반환("이 필터 없음")
- 컨트롤러가 null을 걸러 AND로 reduce — 필터가 늘어도 메서드가 아니라 조각이 하나 는다
- 필터가 하나도 없으면 findAll(무필터 목록)로 폴백, 정렬은 occurredAt desc + id desc

실측 (2026-07-04, 라이브):
```
무필터:                    9건 전체
action=explain (부분일치):  explain 2건만 (POST /api/instances/2·8/explain)
outcome=200:               200 응답만
instanceId=8 & action=backup (AND): 인스턴스 8의 백업 계열만
미인증 -> 401, VIEWER -> 403 (ADMIN 전용 유지)
```
단위 테스트 8건(필터 단독·조합·기간·인가)으로 동적 WHERE를 고정. 웹 콘솔 Monitoring 탭에
검색 카드 추가(사용자·action·결과코드 입력 + 표).

이로써 Spring Data는 세 층위에서 제자리를 지킨다: 파생 메서드(정적 단순), @Query(정적 집계·벌크),
Specification(동적 필터). "어디에 뭘 쓰나"를 기능이 결정하게 두는 것 — 프레임워크를 과시하지 않는다.

![감사 로그 검색 — 동적 필터(Specification)](images/webui/09-audit.png)

## 32. 정리 아크 — Lombok(R1) + JdbcTemplate(R2): "JPA/Native Query 통일"이 아닌 적재적소

계기: "Operator 계층도 JPA + Native Query로 추상화하면 깔끔하지 않냐"는 질문. 분석 결과는
반대였다 — Operator는 런타임 등록되는 N개의 남의 DB에 붙어 시스템 뷰를 읽고 관리 명령을
실행하는 곳이라, JPA(부팅 시점 고정 데이터소스·엔티티 매핑)와 근본적으로 안 맞는다. Native
Query는 "JPA가 실행해주는 raw SQL"일 뿐이라 시스템 뷰에는 매핑할 게 없어 오히려 의식이 는다.
게다가 MongoDB는 JPA 자체가 없다. 그래서 추상화 경계는 JPA/JDBC보다 위(DbmsOperator 인터페이스,
이미 Repository+Impl과 같은 모양)에 두고, 안쪽은 각 기술을 제자리에 쓴다:

- **R1 Lombok** — 값 객체는 record(불변) 유지, JPA 엔티티 6묶음에만 @Getter + @NoArgsConstructor(PROTECTED).
  @Data/@ToString/@EqualsAndHashCode는 엔티티 lazy 연관·hashCode 지뢰라 배제. 손 게터 43개 제거.
- **R2 JdbcTemplate** — Operator의 raw try-with-resources+ResultSet 루프를 JdbcTemplate으로.
  SQL은 한 글자도 안 바꾸고(시스템 뷰 통제 유지), 실행 메커니즘만. 순 -53줄(283 삭제/230 추가).
  세션 지역 PLAN_TABLE(Oracle explain)은 ConnectionCallback로, 서버 사이드 백업은 raw JDBC로
  정직하게 남김 — 무리한 교체로 정확성을 깨지 않는다.

Spring Data가 지키는 자리(정리 후 최종 지도):
- 플랫폼 자기 저장소(1층): JPA — 파생 메서드(정적 단순) / @Query(정적 집계·벌크) / Specification(동적 필터, 31절)
- 대상 DB 조회(2층): JdbcTemplate(JDBC 계열) + Mongo 드라이버(비 JDBC)
- 추상화 경계: DbmsOperator 인터페이스 — 기종·기술 차이를 그 뒤로 숨긴다

실측: 리팩터 후 실 8080 앱에서 5기종 health·query-stats(RowMapper)·explain(Oracle
ConnectionCallback: TABLE ACCESS FULL 판정)·replication 전부 리팩터 전과 동일. 테스트 91건 통과.

## 33. Phase B2 — 블로킹 트리 + 세션 관리: "지금 누가 누구를 막고 있나"

Wait Event(26절)가 "무엇을 기다렸나"라면, 이건 "누가 막고 있나"와 "어떻게 푸나"다.
DbmsOperator에 activeSessions()·killSession() 2메서드 추가로 5기종 통합.

기종별 세션 소스·kill:
| 기종 | 소스 | blockedBy | kill (cancel / terminate) |
|---|---|---|---|
| PostgreSQL | pg_stat_activity | pg_blocking_pids(pid)[1] | pg_cancel_backend / pg_terminate_backend |
| MySQL | information_schema.PROCESSLIST + sys.innodb_lock_waits | blocking_pid | KILL QUERY id / KILL id |
| SQL Server | dm_exec_requests + dm_exec_sessions | blocking_session_id | KILL (구분 없음) |
| Oracle | v$session(+v$sql) | blocking_session | ALTER SYSTEM KILL SESSION 'sid,serial#' |
| MongoDB | currentOp inprog | null(N/A — 락 모델상 특정 불가, 정직 표기) | killOp(협조적) |

안전장치: killSession은 명시적 pid 하나만(대량·와일드카드 없음), 5기종 모두 자기 수집 커넥션
kill 방지. kill은 MCP 도구로 미노출(에이전트가 세션 죽이는 위험 회피) — 읽기 도구 sessions만(9→10종).
POST kill은 ADMIN만 + A6 자동 감사.

실측 (2026-07-04, PostgreSQL 완주):
```
GET sessions -> pg_sleep(60) 세션 pid 4742, waitEvent=PgSleep 잡힘
POST .../kill?force=false -> pg_cancel_backend true, 재조회 [], sleeper "canceling statement"
블로킹 트리: 2트랜잭션 락 충돌 -> blocked pid가 blockedByPid=blocker로 정확히 채움
POST .../kill?force=true -> pg_terminate_backend true, 막혔던 트랜잭션 즉시 진행
MCP tools/list -> 10종(sessions 포함), 실 8080에서 PgSleep 세션 조회 확인
```
라이브 검증이 버그 2개를 잡았다: (1) rs.wasNull()을 다른 컬럼 read 뒤 호출해 blocked_by null이
0으로 샘 -> getLong 직후 캡처로 수정, (2) pg_cancel_backend가 int4 인자라 Java long 바인딩 실패
-> (?)::int 캐스팅. "동작한다"를 라이브로 증명하지 않았으면 못 잡을 것들이다.

## 34. Phase B8 — DB팀 문의 채널: 레퍼런스 3단계 완성

한계: 레퍼런스 흐름은 1.식별 2.분석 3.DB팀 문의인데, 3단계(분석 결과를 첨부해 DBA에게 문의)가
비어 있었다. 회귀 감지 웹훅이 "플랫폼이 미는 push"라면, 문의는 "사람이 분석을 첨부해 보내는 push".

- InquiryService/Controller를 alert 모듈에 배치 — insight에 두면 WebhookNotifier(alert) 참조로
  insight<->alert 순환이 되므로(RegressionDetector가 이미 alert->insight). ModularityTests로 확인
- 쿼리·실행계획·규칙 지적·AI 분석·비고를 플레인 텍스트로 포맷해 WebhookNotifier로 전송
- 웹훅 미설정이면 sent:false + 이유(외부 전송 안 함), 인증 사용자면 VIEWER도 문의 가능(협업)
- 웹 콘솔 쿼리 상세 패널에 "DB팀에 문의" 버튼 — explain/AI 결과를 캐시해 함께 첨부

실측: 웹훅 미설정 경로 sent:false 확인(외부 전송 0건), 전송 경로는 WebhookNotifier mock 단위
테스트로 증명. POST라 A6 자동 감사. 이로써 레퍼런스 1·2·3단계가 전부 채워졌다.

## 35. Phase B3 — 인덱스 어드바이저: "이 인덱스를 만들면 플랜이 어떻게 바뀌나"

explain(2절)이 "인덱스가 없다"까지 지적하니, 다음은 "만들면 어떻게 되나"다. 핵심 가치는
실제 인덱스를 만들지 않고 시뮬레이션하는 것 — 운영 DB에 인덱스를 함부로 만들 수 없으니까.

- PostgreSQL: HypoPG 확장으로 가상 인덱스 생성 → 같은 커넥션에서 EXPLAIN 재실행 →
  Total Cost 비교 → hypopg_reset(). ConnectionCallback으로 한 커넥션에 묶는다.
  결과 3값: ADVISED(비용 유의미 감소) / NO_BENEFIT(옵티마이저 미채택) / UNSUPPORTED
- 나머지 4기종: UNSUPPORTED — 가상 인덱스 시뮬레이션은 PG(HypoPG)만. 통과로 위장 안 함(A7 원칙)
- 안전: SELECT 전용 + 식별자 화이트리스트(인젝션 방어), 가상이라 대상 DB 불변

실측 (2026-07-04, PostgreSQL HypoPG):
```
category 무인덱스 등치 쿼리 -> status ADVISED,
  beforeCost 320.00 -> afterCost 122.36 (제안: CREATE INDEX ON advisor_demo (category))
DELETE 쿼리 -> 400 (SELECT 전용 방어)
검증 후 pg_indexes -> 0 (실제 인덱스 안 생김, 가상만 — 이 기능의 핵심)
```
HypoPG는 stock 이미지에 없어 설치가 필요했다(postgresql-16-hypopg) — 이것이 이 기능의 전제 조건.

## 36. Phase B7 — Schema Diff: "왜 저 장비만 다르지"

한계: 같은 역할의 두 인스턴스(스테이징 vs 운영 등)가 미묘하게 다를 때 원인을 눈으로 못 찾는다.

- DbmsOperator.describeSchema() 5기종 — SchemaSnapshot(테이블·컬럼·인덱스 구조 요약).
  완전한 DDL 재현이 아니라 diff에 필요한 최소 공통 모델임을 명시(기종차 흡수).
- SchemaDiffService(insight 모듈): 추가/삭제/변경 테이블·컬럼·인덱스 3분류.
  operator=읽기, insight=비교로 순환 회피(ModularityTests 통과).
- 기종 혼합 비교 시 경고, 테이블 상한 200(초과 시 truncated 표기).
- MongoDB는 스키마리스 — 컬렉션·인덱스만 비교(컬럼 없음, 정직 표기).

실측: 3기종(MySQL·PostgreSQL·MongoDB) 실구조 읽음, 자기비교 identical:true,
기종혼합(mysql vs mongo) diff는 변경 테이블·경고 정확. MCP 도구 12종(schema·schema_diff 포함).
단위 4건(추가/삭제/타입변경/인덱스, ordinal-only 변경은 비차이).

## 37. Phase B 진행 현황

DBA가 매일 쓰는 진단을 축으로 채웠다. 시점 비교(무엇이 변했나)에 더해:
| # | 기능 | 답하는 질문 | 절 |
|---|---|---|---|
| B1 | Wait Event | 그 시간에 무엇을 기다렸나(CPU/IO/Lock) | 26 |
| B2 | 세션/블로킹 | 지금 누가 누구를 막고 있나, 어떻게 푸나 | 33 |
| B3 | 인덱스 어드바이저 | 이 인덱스를 만들면 어떻게 바뀌나(HypoPG 가상) | 35 |
| B7 | Schema Diff | 왜 저 장비만 다른가 | 36 |
| B8 | DB팀 문의 | 분석을 첨부해 어떻게 넘기나(레퍼런스 3단계) | 34 |

남은 Phase B: B4(gh-ost 온라인 스키마 변경), B5(장기 트랜잭션·복제 지연·용량 알림), B6(파라미터 드리프트).
5기종 통합은 전부 "DbmsOperator 메서드 1개 추가"로 이뤄졌다 — 아키텍처 주장의 반복 검증.

## 38. Phase B4 — 온라인 스키마 변경(gh-ost): 대형 ALTER를 락 최소화로

한계: 대형 테이블 ALTER는 락으로 서비스를 멈춘다. gh-ost는 그림자 테이블+binlog로 무중단
변경하는 검증된 도구 — 플랫폼이 이를 오케스트레이션한다(MySQL 전용).

- onlineddl 모듈: 테이블·ALTER 절을 받아 gh-ost 명령 구성·실행. 기본 dry-run(noop),
  실제 실행(--execute)은 웹 confirm + ADMIN. 3값 OK/FAILED/UNSUPPORTED.
- 비 MySQL·gh-ost 바이너리 부재는 UNSUPPORTED(위장 없음, A7 원칙). 식별자 화이트리스트 +
  ALTER 절 검증(세미콜론·제어문자 거부).
- **비밀번호 비노출(정직성 핵심)**: gh-ost는 env/stdin 경로가 없다(실측: -ask-pass는 TTY 전용,
  MYSQL_PWD 미지원). 유일한 argv 회피책은 소유자 전용(0600) 임시 conf 파일 + 실행 직후 삭제.

실측 (2026-07-04, gh-ost 1.1.10 brew 설치):
```
버릴 테이블 sample.b4_demo(5000행):
  noop  -> OK, 실제 변경 없음, 고스트 테이블 잔여 0
  execute -> OK, b4_added 컬럼 추가 확인, 잔여 0 (검증 후 drop)
실 8080: MySQL b4_live noop -> OK, PostgreSQL -> UNSUPPORTED(정직)
비밀번호: 앱 로그 0건, 임시 conf 잔여 0건, 내 파일에 비밀번호 0건
필요 플래그 실측: --allow-on-master --assume-rbr --initially-drop-ghost-table --ok-to-drop-table
  (로컬 포트매핑 13306->3306 때문에 @@port 검사만 --azure로 우회, 운영선 제거)
```
MCP 미노출 — 스키마 변경을 에이전트가 실행하는 건 위험이라 도구로 안 연다(12종 유지).

## 39. Phase B5 — 운영 알림 확장: 성능 회귀 밖의 신호

회귀 감지(쿼리 성능)에 더해, 운영이 무너지는 다른 신호를 폴러가 잡는다. operator 인터페이스
변경 0 — 기존 activeSessions()·replicationState()와 스냅샷 리포를 재사용한 것이 설계 핵심.

- OpsAlertDetector(@SchedulerLock HA): 장기 idle-in-transaction(락·VACUUM 차단 원인),
  복제 지연(lagSeconds 임계), 스냅샷 수집 정지. 쿨다운·예외 격리(폴러 무중단).
- 웹훅 미설정이면 조용히 비활성(회귀 감지와 동일).

실측: idle in transaction 세션(pid 5785, 21s > 임계 5s) 감지 로그 확인, sessions API로 교차 확인.
실 8080에 ops-alert-detect ShedLock 등록·무에러. 단위 6건(임계·상태·STANDALONE·음수lag·쿨다운·예외).

## 40. Phase B6 — 파라미터 드리프트: "왜 저 장비만 다르지"

한계: 같은 역할의 두 인스턴스가 설정값 하나 때문에 다르게 동작한다(work_mem, max_connections 등).

- parameters() 5기종(pg_settings·SHOW VARIABLES·sys.configurations·v$parameter·getParameter),
  ParameterDiffService(insight): 값 다른 항목·한쪽에만·기종혼합 경고. B7 Schema Diff와 같은 구조.
- 민감값 이름 휴리스틱 마스킹(password/secret/key -> ***), 마스킹 토큰이 좌우 동일해 diff 오탐 없음.
- 파라미터는 값 자체가 인프라 형상·자격증명이라 ADMIN 제한(구조만 보는 Schema Diff와 다른 경계).

실측: PG 368개·MySQL 623개 실조회, PG ssl_key_file -> *** 마스킹, 기종혼합 diff·자기비교 identical.
단위 6건.

## 41. Phase B 완결 (B1~B8, 일부 제외)

DBA가 장애 시 던지는 질문을 축으로 진단 심화를 채웠다:
| # | 질문 | 절 |
|---|---|---|
| B1 | 무엇을 기다렸나 (Wait Event) | 26 |
| B2 | 누가 누구를 막나, 어떻게 푸나 (세션/블로킹) | 33 |
| B3 | 인덱스 만들면 어떻게 되나 (HypoPG) | 35 |
| B4 | 대형 ALTER를 어떻게 무중단으로 (gh-ost) | 38 |
| B5 | 성능 밖 운영 신호 (idle-txn·복제지연 알림) | 39 |
| B6 | 왜 저 장비만 다른가 (파라미터 드리프트) | 40 |
| B7 | 스키마가 어디서 갈렸나 (Schema Diff) | 36 |
| B8 | 분석을 어떻게 넘기나 (DB팀 문의, 레퍼런스 3단계) | 34 |

Phase A(운영 안전 8) + Phase B(진단 심화 8) 완료. 새 능력은 전부 "DbmsOperator 메서드 1개 추가"
또는 "기존 메서드 재사용"으로 5기종 통합 — 아키텍처 주장의 반복 검증. 남은 것: Phase C(프로비저닝
연동 — K8s Operator·Terraform·Ansible로 생성-등록 자동화).

## 42. Phase C 프로비저닝 연동 — 멱등 등록(upsert): IaC 재실행 안전장치

지금까지는 "이미 존재하는 DB"를 수동 등록했다. 현업에서 DB는 IaC로 태어난다 — 태어나는
순간 관제탑에 자동 등록되는 것이 Phase C다. 그 전제가 **멱등 등록**: 프로비저닝이 등록을
재실행해도 중복이 아니라 갱신이어야 한다.

- PUT /api/instances: 같은 이름이면 접속 정보 갱신(풀 정리 후), 없으면 신규. 어느 쪽이든 접속 검증.
- 이름은 논리 식별자(유지), createdAt 유지("언제부터 관제했나"). ADMIN 경계.

실측: 같은 이름 재 PUT에도 id 유지·중복 0. 단위 3건(신규/갱신/접속실패 거부).

## 43. Phase C — Kubernetes(CloudNativePG): 생성→등록 e2e 완주

한계: DB 생성과 관제가 끊겨 있으면 도구 모음이지 플랫폼이 아니다. K8s에서 Operator가
Day-1/2를 맡고, 생성된 DB가 관제탑에 자동 등록되게 잇는다.

실측 (2026-07-05, kind 로컬 클러스터에서 실제 e2e 완주):
```
kind create cluster (v0.32) -> Docker 노드 기동
CloudNativePG operator 1.24.1 설치 -> cnpg-controller-manager Available
kubectl apply cluster.yml -> Operator가 프로비저닝, cluster/dbtower-pg "healthy", pod Running,
  접속 Secret dbtower-pg-app(username/password/host/port/dbname) 자동 생성
register-job 로직: -app Secret 읽어 PUT /api/instances -> 등록 id 1
DBTower가 그 DB에 실제 접속: health up, "PostgreSQL 16.4" (pingMillis 47)
멱등: 등록 재실행 -> id 1 유지(중복 0)
kind delete cluster (정리)
```
infra/k8s/: Cluster CR + register-job(Secret 마운트 -> PUT) + config Secret 예시. 매니페스트
YAML 문법 검증 통과. 등록 훅은 CloudNativePG의 -app Secret 규약을 그대로 읽는다.

## 44. Phase C — Ansible(VM) 완주 / Terraform(RDS) validate

**Ansible (온프레미스/VM — 실제 e2e 완주, 2026-07-05):**
```
대상 dbtower-postgres(15432)에 register-db.yml 실행:
  1차: 모니터링 계정 생성 + pg_read_all_stats 부여 + PUT 등록 -> changed=1, "등록 완료 HTTP 200"
  2차: 멱등 -> changed=0 (중복·에러 없음)
DBTower에 prod-postgres-01 등록 확인(개수 1), 최소권한 계정으로 health up "PostgreSQL 16.14"
```
계정 생성은 community.postgresql(psycopg2), 등록은 uri 모듈 PUT. 비밀은 secrets.yml(gitignore).

**Terraform (클라우드/RDS — validate까지, apply 안 함):**
```
OpenTofu v1.12.3, aws provider v5.100:
  tofu init -> provider 설치, tofu fmt -> 정상, tofu validate -> "configuration is valid"
```
aws_db_instance(RDS) + 생성 후 local-exec PUT 등록. **apply는 실행하지 않았다** — 실제 RDS는
AWS 자격증명·과금이 필요하기 때문(정직성). 같은 등록 흐름의 실제 완주는 K8s·Ansible에서 확인됨.

## 45. Phase C 완결 — 세 층에서 "생성과 관제를 잇는다"

| 환경 | 도구 | 검증 수준 |
|---|---|---|
| Kubernetes | CloudNativePG Operator + 등록 Job | **e2e 완주** (kind: 프로비저닝→Secret→등록→health up) |
| 온프레미스/VM | Ansible 플레이북 | **e2e 완주** (계정 생성→등록, 멱등 changed=0) |
| 클라우드 | Terraform(OpenTofu) RDS 모듈 | validate 통과 (apply는 자격증명 필요 — 정직하게 미실행) |

셋 다 DBTower의 멱등 등록 PUT을 종점으로 쓴다. "생성과 관제가 이어져야 플랫폼이고, 끊어져
있으면 도구 모음이다"(ROADMAP Phase C)를 세 판에서 채웠다. 비밀값은 전부 gitignore 분리.

## 46. Phase D 배치1 — 자율 진단 3종 (D1 이상감지·D2 Advisors·D4a 백분위)

방향 근거: 2026 DBA 설문(알림 피로 75%·조용한 저하 미탐지)과 실존 제품(AWS DevOps Guru·Percona
PMM Advisors·pganalyze). "사람이 모는 대시보드"에서 "스스로 보는 관제탑"으로. 전부 읽기·진단만,
5기종 유지, 정체성 이탈 없음.

### 46-1. D1 이상 자동 감지 (AWS DevOps Guru 모델)
스냅샷 이력으로 인스턴스·쿼리별 요일×시간대 베이스라인(평균·표준편차) 학습 → z-score 이탈 감지.
고정 임계(RegressionDetector +200%)를 "평소 대비"로 승격, 둘이 공존. 데이터 부족은 "학습 중"으로 보류.
실측: COUNT digest qps 25.0 vs 베이스라인 0.17±0.07 → z=378, 고정 임계 없이 감지. 폴러 end-to-end
발화 확인(qps 55.0, 평소 5.17±12.22, z=4.1). 실 8080: /anomalies 스키마 정상(learningCount로 학습 상태).

### 46-2. D2 Advisors 자동 점검 (Percona PMM 모델)
operations.md·least-privilege.md의 실측 규칙을 코드 Advisor 6종으로. **operator 인터페이스 변경 0** —
기존 parameters()/describeSchema()/tableStats()/queryStats()를 재사용해 판정. 일일 스윕(@SchedulerLock HA).
실측(실 8080 MySQL): "digest 테이블 포화 위험" VIOLATIONS, "위험 파라미터값" VIOLATIONS, 중복 인덱스 OK,
모니터링 권한 UNSUPPORTED. 기종별 적용 가능한 것만, 무관은 UNSUPPORTED 정직.

### 46-3. D4a 레이턴시 백분위 (이기종 정직)
`latencyPercentiles()` — 같은 p95인데 기종마다 소스가 다르다:
- MySQL NATIVE: events_statements_summary_by_digest의 QUANTILE_95/99 컬럼(실 8080 p95=19.95ms)
- MongoDB COMPUTED: system.profile 원샘플 계산 / PostgreSQL ESTIMATED: mean+1.645×stddev(추정 라벨)
- MSSQL/Oracle UNSUPPORTED(실 8080 Oracle 확인). 네 source 라벨 안 섞음, MySQL 누적 한계 표기.

배치1 병합 후 실 8080 재검증 완료(Docker 데몬 재기동 포함), 전체 테스트 통과.

## 47. Phase D 배치2 — 자율 진단 심화 (D3 자연어 진단·D5 파티션·D7 백업 신선도)

### 47-1. D3 자연어 근본원인 진단 (레퍼런스 자율 AI·pganalyze 모델)
단발 AI 분석(AiAnalyzer)을 **도구 사용 루프**로 승격. 질문 → AI가 어떤 MCP 도구를 부를지 스스로
정하면 서버가 McpProtocolHandler로 실행 → 결과를 누적해 다시 AI 호출(최대 5스텝) → 근본원인 종합.
read-only 12종 화이트리스트로 쓰기 도구(kill·backup·online-ddl) 이중 차단 — 대상 DB 변경 0.
실측(claude CLI 백엔드): LIKE 풀스캔 부하에 query_stats→explain을 실제 연쇄해 access_type=ALL 원인을
판단 기준 문서(앞 와일드카드 LIKE→B+Tree 시작점 불가)로 서술, confidence=high. 실 8080에서도
backend=cli로 도구 호출·high 확인. 근거 없는 질문("작년 크리스마스 접속자")은 수치 안 지어내고 low.

### 47-2. D5 파티션 조회 (레퍼런스 6기능 마지막 조각)
partitions() 5기종 — MySQL information_schema.PARTITIONS, PG pg_partitioned_table+relpartbound,
Oracle user_tab_partitions, MSSQL sys.partitions+scheme/function, Mongo UNSUPPORTED(관계형 파티셔닝
없음). 조회 전용(생성·삭제·자동관리는 범위 밖). MCP 13종. 실측: 직접 만든 파티션 테이블로 4기종
실조회+정리(대상 데이터 무손상), 파티션 없으면 빈 결과 200. BigInteger/BigDecimal 캐스팅 버그 수정.

### 47-3. D7 백업 신선도·커버리지 (3-2-1 원칙)
BackupRun 이력에서 인스턴스별 최신 SUCCESS 백업 → FRESH/STALE/NO_BACKUP + A7 복원 검증 상태.
메타 DB만 읽어 판정(대상 접속 실패와 무관 — 대상이 죽었을 때가 백업 신선도가 가장 중요), 나쁜 순 정렬.
OpsAlertDetector에 신선도 경보(STALE 항상·NO_BACKUP은 등록 후 임계 창 지난 것만). 실측(실 8080):
6개 인스턴스를 FRESH 1·STALE 2·NO_BACKUP 3으로 분류, NO_BACKUP 상단 정렬 확인.

## 48. Phase D 배치3 — SLO·FinOps (D4·D6)

### 48-1. D4 DB SLO / 에러 버짓 (Google SRE·DBRE)
"인프라 지표(CPU) 아니라 사용자 경험 지표" 원칙. 레이턴시 SLI는 D4a latencyPercentiles(p95/p99)
재사용, UNSUPPORTED 기종(MSSQL/Oracle)은 평균 레이턴시로 폴백하고 source=AVG_FALLBACK 정직 표기.
가용성 SLI는 HealthSample 이력(SloHealthPoller가 적재)의 up 비율. 에러 버짓=허용 다운타임 대비
소진율+번인 레이트, EXHAUSTED/WARNING/OK. V5__health_sample.sql(실 DB 적용 확인).
실측(실 8080): MySQL 레이턴시 source=NATIVE·BREACHING, Oracle source=AVG_FALLBACK, 가용성 MEETING.
단위 12건(최악 p95·폴백·버짓 산식·번인·데이터 부족).

### 48-2. D6 비용/효율 인사이트 (AWS FinOps·Mydbops)
낭비 후보를 "신호"까지만 — 절감액(달러)·자동 실행 없음. DbmsOperator.indexUsage()로 미사용
인덱스를 실제 사용 카운터(PG idx_scan·MySQL COUNT_STAR·MSSQL·Mongo NATIVE, Oracle UNSUPPORTED)로,
중복 인덱스는 D2 Advisor 재사용(판정 단일 출처), 큰 테이블·오버프로비저닝 신호. 유니크/PK 인덱스는
미사용이어도 제외(오탐 방지). 실측(실 8080): 직접 만든 미사용 인덱스 PG·MySQL 검출 후 정리,
오버프로비저닝 후보 2건. 단위 5건.

## 49. 심층 원인 진단(D9) 명세 확정 — 프롬프트 문서 강화

사용자 지적("기종별로 왜 인덱스를 못 타는지 디테일하게 분석해야")을 반영해 D9를 웹서칭으로 검증·명세화.
docs/ai-analysis-rules.md에 "심층 원인 규칙(D9)" 절 추가: (1) 추정 vs 실제 행수 괴리를 기종별로 보는
법(MySQL EXPLAIN ANALYZE FORMAT=JSON, PG (ANALYZE,BUFFERS), Oracle gather_plan_statistics+
DISPLAY_CURSOR ALLSTATS LAST, MSSQL SET STATISTICS XML 별도 결과셋, Mongo executionStats),
(2) 인덱스 무력화 근본원인 5종(암시적 형변환·컬럼 함수·통계 노후·낮은 선택도·복합 선두 누락),
(3) 실행 안전(SELECT 전용+타임아웃). 검증된 함정 명시: MySQL/PG actual rows는 loops당 평균(총량=loops 곱),
Oracle 권한은 SELECT_CATALOG_ROLE로 충분, MSSQL은 plain Statement+getMoreResults. 구현은 Phase D9(미착수).

## 50. Phase D 완결 — 자율 진단 (D1~D9)

"사람이 모는 대시보드"에서 "스스로 보고 설명하는 관제탑"으로. 전부 읽기·진단, 5기종 유지, 실측 근거.

| # | 기능 | 실존 제품 근거 | 절 |
|---|---|---|---|
| D1 | 이상 자동 감지(베이스라인) | AWS DevOps Guru for RDS | 46 |
| D2 | Advisors 자동 점검 | Percona PMM Advisors | 46 |
| D3 | 자연어 근본원인 진단(AI 도구 루프) | 레퍼런스·pganalyze | 47 |
| D4 | DB SLO / 에러 버짓 | Google SRE·DBRE | 48 |
| D4a | 레이턴시 백분위(p95/p99) | MySQL QUANTILE·Mongo profile | 46 |
| D5 | 파티션 조회 | 레퍼런스 6기능 | 47 |
| D6 | 비용/효율 인사이트(FinOps) | AWS FinOps·Mydbops | 48 |
| D7 | 백업 신선도·커버리지 | 3-2-1 원칙 | 47 |
| D8 | 통합 헬스 스코어 | 관측성 자동 우선순위 | 50 |
| D9 | 심층 원인 진단(왜 인덱스를 못 타나) | 추정 vs 실제 카디널리티 | 50 |

### 50-1. D8 통합 헬스 스코어
health·D1·D2·D4·D7을 인스턴스별 0~100+등급으로 합산(가중치 설정), 감점 사유 분해, 나쁜 순 정렬.
"데이터 부족(INSUFFICIENT_DATA)"과 "나쁨" 구분, health 프로브 예외는 down으로 수렴(치명). 신호 격리(partial).
실측(실 8080): local-mysql/mssql F 52점, 나머지 B~88점 나쁜 순. canary kill 시 F 35점 최상단. 단위 21건.

### 50-2. D9 심층 원인 진단
explainAnalyze() 5기종 실제 실행 계획(추정 아님) — MySQL EXPLAIN ANALYZE(TREE, 8.4는 FORMAT=JSON
거부라 실측대로 TREE), PG (ANALYZE,BUFFERS,FORMAT JSON), Oracle gather_plan_statistics+DISPLAY_CURSOR
ALLSTATS LAST(같은 커넥션), MSSQL SET STATISTICS XML(별도 결과셋), Mongo executionStats. SELECT 전용+
타임아웃(실제 실행이라 ADMIN 경계). 추정 vs 실제 10배+ 괴리 최하위 노드 지목(MySQL/PG loops 곱·Oracle
A-Rows 총량), 근본원인 5종(형변환·컬럼함수·앞와일드카드·복합선두누락·통계노후).
실측(실 8080, 핵심): MySQL `code = 12345`(숫자) -> 풀스캔 + "암시적 형변환" 정확 지목 + 처방("문자열로
주거나 컬럼 타입 맞춰라"), `'012345'`(문자열) -> 근본원인 없음 정상. PG 추정5 vs 실제348(69.6배) 앞
와일드카드, Mongo docsExamined 2만배, PG pg_sleep 타임아웃 10초 취소. 단위 12건(loops 곱 정확성 포함).

Phase A(운영 안전 8)·B(진단 심화 8)·C(프로비저닝 4)·D(자율 진단 10) 전부 완료. 새 능력은 전부
DbmsOperator 메서드 1개 추가 또는 기존 재사용으로 5기종 통합 — 아키텍처 주장의 반복 검증.

## 51. A9 분석 보호장치 — 진단이 부하 유발자가 되지 않게

원칙: 관제 도구가 대상 DB를 오래 붙잡거나(무거운 조회) 죽은 대상을 매 틱 두드려서(재접속 부하)
스스로 부하 유발자가 되면 안 된다. Datadog DBM도 수집 쿼리에 statement timeout을 건다.

세 축으로 구현하고 단일 지점에 넣어 전 조회가 상속하게 했다:

1. **JDBC 기본 쿼리 타임아웃** — `AbstractJdbcOperator.jdbc()` 헬퍼 한 곳에서 `setQueryTimeout(
   pools.queryTimeoutSeconds())`. query-stats·wait-events·sessions 등 모든 JDBC 조회가 상속.
   설정 `dbtower.query-timeout-seconds`(기본 15). 심층진단(explain 실제 실행)은 이와 별개로 더
   짧은 명시적 타임아웃을 유지(50절, ADMIN 경계).
2. **Mongo 소켓 read 상한** — `MongoClientCache`가 같은 `queryTimeoutSeconds()`를 소켓 readTimeout에
   연결. CSOT(.timeout())를 안 쓰는 이유는 D9가 명령에 실은 명시적 maxTimeMS와 간섭하기 때문(정직 명시).
   무거운 실행 경로(explain executionStats)는 서버측 maxTimeMS로 별도 상한.
3. **수집 폴러 지수 백오프** — `SnapshotScheduler`가 인스턴스별 연속 실패를 세어 건너뛸 틱을 2^(n-1)로
   증가(상한 16), 1회 성공 즉시 상태 제거로 정상 주기 복귀. 한 인스턴스 실패는 나머지 수집을 막지 않는 격리 유지.

실측(실 8080, query-timeout=3 오버라이드 부팅):
- Mongo readTimeout이 설정을 그대로 반영 — 부팅 로그 MongoClientSettings `socketSettings{readTimeoutMS=3000}`
  (이전 하드코딩 15000 → 설정값 3000). config가 ConnectionPools에 바인딩되어 MongoClientCache로 전파됨을 증명.
- 백오프 라이브: 닿지 않는 포트(59999) 죽은 인스턴스 등록 후 SnapshotScheduler 로그 —
  `22:13:00 실패 다음_건너뛸틱=1` → (다음 틱 canary 건너뜀, 다른 6개는 정상 수집) → `22:14:54 실패 다음_건너뛸틱=2`.
  =1과 =2 사이 ~114초(정상 틱 ~56초의 2배)로 한 틱을 통째로 건너뛴 뒤 재시도해 백오프가 2로 증가함을 확인.
- 단위 4건(지수 수열 1→2→4→8→16→포화, 성공 즉시 복귀, skip 예산 소진 후 재시도, 실패 이력 없으면 미건너뜀).
  전체 스위트·ModularityTests·Spring 컨텍스트 로드(신규 MongoClientCache→ConnectionPools 와이어링·설정 바인딩) 그린.

Phase A 8개 축 전부 완료(로드맵 A 항목 9/9). Phase A~D 전 항목 완료.

## 52. Phase E — 셀프호스트 제품화 (컨테이너 이미지·원커맨드 배포·GHCR)

왜 셀프호스트인가: SaaS 는 대상 DB 자격증명 수탁·사설망 도달·멀티테넌시·비용 네 벽에 막힌다.
Grafana/PMM 처럼 사용자가 자기 인프라에 도구를 띄우고 자기 DB 를 붙이는 모델로 간다.

산출물:
- **Dockerfile**(멀티스테이지): 빌드 스테이지에서 `clean bootJar -x test`(build/jar 를 부르면 생기는
  실행 불가 `-plain.jar` 회피). 런타임은 `eclipse-temurin:21-jre` + 비루트(uid 1001) + actuator HEALTHCHECK.
- **배터리 포함**: 백업/복원이 shell-out 하는 클라이언트 번들 — 셀프호스트 사용자가 추가 설치 없이 백업 가능
  ("정직하게 동작하는 제품"). SQL Server 백업은 서버사이드 T-SQL, Oracle 은 UNSUPPORTED 라 그 둘의 CLI 는 불필요.
- **application-docker.yml**: 컨테이너 프로파일. 로컬 데브의 docker-exec 백업 명령을 "대상에 직접
  네트워크 접속(mysqldump -h {host} ...)" 형태로 덮어씀. 로컬 application.yml 은 불변(데브 환경 보존).
- **docker-compose.app.yml + .env.example**: 앱 + 전용 메타 DB 원커맨드. `.gitignore` 에 `.env` 추가.
- **release.yml**: `vX.Y.Z` 태그 push 시 GHCR 게시(docker/metadata-action semver 자동 태깅, 게시 전
  `./gradlew test` 게이트). 게시는 사용자가 태그를 push 할 때만 — 공개 행위는 사용자 트리거.
- build.gradle 버전 `0.0.1-SNAPSHOT → 1.0.0`.

실측(이미지 빌드·기동, 기존 데모와 격리된 별도 프로젝트/포트):
- `docker build` 성공, 이미지 751MB(배터리 포함 규모). 멀티스테이지에서 bootJar 단일 산출 확인.
- `docker compose -f docker-compose.app.yml up -d`(포트 18080): meta-db pg_isready Healthy 후 앱 기동,
  프로파일 `docker` 활성 로그, 신규 메타 DB에 Flyway 마이그레이션 후 `/actuator/health` 200(readiness UP).
- 번들 클라이언트 컨테이너 내 실행 검증: `mysqldump Ver 8.0.46`, `pg_dump (PostgreSQL) 16.14`
  (PGDG — PG16 서버와 버전 일치, 로컬 데브가 겪던 스큐 없음), `mongodump 100.17.0`.
- 검증 후 스택·이미지 정리, 기존 데모(9 컨테이너)·데브 앱(8080) 무사 확인.

Phase A(8)·B(8)·C(4)·D(10) 전 기능 + Phase E 제품화 완료. A9 로 로드맵 마지막 기능 항목까지 닫음.

## 53. D9 표현·검증 루프 개선 — 외부 리뷰 반영

발행 후 받은 외부 리뷰 4건을 판정해 3.5건 수용(코드·프롬프트 문서·UI), 검증 루프 1건 신규.

1. **원인 -> 증상 표시 순서**: 첫 카드가 "카디널리티 오추정"이면 사용자가 "통계 갱신(ANALYZE)"이라는
   엉뚱한 처방으로 빠질 수 있다 — 근본원인이 있으면 원인을 먼저, 괴리는 "증상(위 원인의 부산물,
   통계 갱신으로는 안 풀린다)"으로 격하. 원인 미발견 시에만 괴리가 헤드라인(그때는 유일한 단서).
2. **형변환 판정문 재구성 + 정합성 경고**: 메커니즘(비교 규칙상 문자열 쪽이 숫자로 캐스팅 —
   CAST(col AS DOUBLE)=N 꼴, 컬럼에 함수를 씌운 것과 동일)을 먼저, 그리고 성능보다 위험한
   정합성('0N'·' N'·'Nabc'도 전부 매칭 — 조회면 오답, UPDATE/DELETE면 데이터 사고)을 명시.
   ai-analysis-rules.md도 동일 갱신 — AI 프롬프트는 이 문서를 런타임 로드하므로 코드 수정 없이 반영.
3. **loops 환산 노트 조건부**: loops>1 노드를 실제로 봤을 때만 표기(loops=1이면 노이즈).
4. **수정안 원클릭 재진단(신규)**: 기계적으로 안전한 수정(숫자 리터럴에 따옴표)이 가능한 형변환
   케이스에 suggestedSql을 실어 UI 버튼 한 번으로 재진단 — before/after 비교 스트립 표시.

실측(실 8080): `code = 12345` -> 근본 원인 카드가 최상단(정합성·캐스팅 명시), loops 노트 없음(loops=1),
suggestedSql 정확. 버튼 클릭 -> "수정 전 -> 후 — 괴리 300배 -> 괴리 없음, 근본원인 1건 -> 0건" +
Index lookup using idx_code. 덤: 수정 전 "실제 1행"은 '012345'가 캐스팅으로 잘못 매칭된 행이었고
수정 후 0행 — 정합성 경고가 실측으로 증명됨. 단위 3건 추가(정합성 문구·suggestedSql·loops 조건부).

## 54. 심화 1 — TLS 강제 접속: 관리형 서비스의 벽 제거

한계 인지: 접속 문자열이 로컬/사내망 기준(MSSQL encrypt=false 하드코딩, Mongo TLS off)이라
Atlas·Azure SQL·RDS(rds.force_ssl) 같은 TLS 강제 서비스에 붙을 수 없었다 — 셀프호스트
사용자가 실사용에서 처음 부딪힐 벽.

구현: 인스턴스 옵션 `useTls`(V6, 기존 행 FALSE 하위 호환, 미지정 페이로드 호환) —
기종별 반영: MySQL sslMode=REQUIRED / PG sslmode=require / MSSQL encrypt=true +
trustServerCertificate=false / Oracle tcps / Mongo sslSettings. **검증 우회 옵션은 일부러
안 만들었다** — trustServerCertificate=true류는 "TLS를 켰다"는 착각만 주는 보안 구멍이다.
비TLS URL은 바이트 단위로 기존과 동일(단위 테스트로 고정).

실측(실 8080):
- MySQL 8.4(자동 생성 인증서) useTls=true 등록 -> **201 + health up** — 등록이 fail-closed라
  성공 자체가 REQUIRED 협상 성공의 증거
- MSSQL(자가서명) useTls=true 등록 -> **접속 실패로 등록 거부** — 인증서 체인 검증이
  실제로 작동한다는 실측. 신뢰하려면 truststore 등록이 정도(正道)
- 단위 4건(기종별 URL, 하위 호환 바이트 동일성, 검증 우회 부재)

## 55. 심화 2 — 백업 원격 보관: 3-2-1의 오프사이트 완성

한계 인지(29절에서 정직 명시했던 잔여): 로컬 디스크에만 있는 백업은 서버가 죽으면 같이 죽는다.

구현: 성공 백업을 S3 호환 스토리지에 업로드(AWS SDK, MinIO는 path-style). S3 "호환"인 이유 —
셀프호스트 사용자가 클라우드 계정 없이 MinIO 컨테이너 하나로 오프사이트를 갖출 수 있다.
**업로드 실패는 백업 실패가 아니다** — 로컬 성공은 유효한 사실이므로 이력은 SUCCESS 유지,
remoteLocation만 null(별개 사실로 기록). 자격증명은 환경변수로만. BackupRun.remoteLocation(V7),
신선도 카드에 "원격 보관/로컬만" 열 추가. 데모 스택에 MinIO 추가(콘솔 19001).

실측(실 8080 + MinIO): local-postgres 즉시 백업 -> SUCCESS 32,892,191 bytes,
remoteLocation=s3://dbtower-backups/instance-2/postgres-...sql, MinIO 컨테이너 /data에서
객체 실재 확인. 서버 사이드 백업(MSSQL/Oracle)은 산출물이 로컬 파일이 아니라 스킵 노트(정직).

## 56. 심화 3 — 실행계획 변경(plan flip) 감지: "쿼리는 그대로인데 갑자기 느려요"

한계 인지: 회귀 감지는 "느려졌다"까지만 답했다. 현업 단골 장애 — 배포도 데이터 변화도 없는데
옵티마이저가 플랜을 갈아탐(pganalyze plan change alerts·PMM QAN이 잡는 그것) — 은 못 짚었다.

설계: 회귀(레이턴시/행수)가 <b>이미 감지된</b> 쿼리만 추정 explain을 떠서(실행 부하 0, A9 원칙)
계획의 <b>형태(shape)</b>만 해시 비교 — 노드 종류·인덱스·대상만 남기고 비용·추정 행수는 버린다
(통계만 변해도 흔들려 가짜 변경이 되므로, "같은 구조 다른 추정치 = 같은 플랜"을 단위로 고정).
첫 관측은 기준선. 변경 시 회귀 알림에 "실행계획 변경 확인: A -> B"가 붙고,
GET /api/instances/{id}/plan-changes + 웹 콘솔 카드로 조회된다. PlanSnapshot(V8).

구현에서 밟은 함정 둘(실측으로 발견):
1. **정규화 텍스트의 벽** — 통계 소스의 텍스트는 $1·? 형태라 일반 EXPLAIN이 거부한다. PG 16의
   EXPLAIN (GENERIC_PLAN)이 정확히 이 용도. 타 기종은 플레이스홀더 없는 텍스트만 시도(임의 값
   채우기는 값에 따라 다른 플랜 = 가짜 변경이라 배제). 텍스트 계획 폴백(숫자 제거)은 Oracle
   실이력으로도 검증됨.
2. **pgjdbc extended protocol 함정** — psql(simple protocol)에서 되던 GENERIC_PLAN+$1이 JDBC에선
   서버가 $1을 바인드 파라미터로 파싱해 실패. 이 호출만 1회용 preferQueryMode=simple 커넥션으로
   해결(회귀 시에만 드물게 실행되므로 비용 수용). "수동 검증과 자동화 경로는 다를 수 있다."
   +모의 데이터 함정: 값 2종 스큐 테이블은 제네릭 플랜이 인덱스를 안 써(평균 선택도 50%) 플립이
   성립 불가 — k 고유값 30만 + 범위 쿼리(BETWEEN $1 AND $2)로 시나리오 재설계.

실측(실 8081, 같은 digest 4막 e2e — 윈도우 축소 recent2/base4/쿨다운3):
```
P1 좁은 범위(101행, 인덱스) 4분      -> 평균 0.03ms
P2 넓은 범위(25만행, 같은 digest)   -> 레이턴시 회귀 발화 -> 23:55:47 기준선 저장:
                                       Aggregate>[Index Only Scan(idx_plan_demo_k)]
P3 좁은 범위(회복) 5분              -> 조용 (개선은 회귀가 아니다)
P4 00:00:48 인덱스 드랍 -> 좁은 범위 -> 60초 뒤 00:01:48 감지:
   "실행계획 변경 확인: SELECT count(*) FROM plan_demo WHERE k BETWEEN $1 AND $2
    — Aggregate>[Index Only Scan(idx_plan_demo_k)]
    -> Aggregate>[Gather>[Aggregate>[Seq Scan(plan_demo)]]]"
   (동반 파인딩: 레이턴시 회귀 평균 0.03 -> 6.23ms, +23,249%)
```
단위 5건(shape 정규화 — 추정치 무시·인덱스 차이·중첩 트리·텍스트 폴백). 3-2-1·TLS와 함께
심화 아크 완료 — 새 축 없이 기존 축의 정직한 잔여를 닫았다.

## 57. 심화 아크 2차·1 — 플랜 플립 5기종 완성 (planShapeForDigest)

한계 인지: 플랜 변경 감지(56절)가 PostgreSQL만 완전했다 — 정규화 텍스트($1·?)로 계획을 얻는
길이 기종마다 달라 나머지 4기종은 스킵됐다.

설계: DbmsOperator.planShapeForDigest(queryId, queryText) default 메서드 하나로 각 기종이 최선
경로로 계획을 얻어 정규화 shape를 반환하게 하고(PlanChangeTracker는 엔진 무관해짐), shape 정규화는
PlanShapes 유틸(operator 모듈 — alert↔operator 순환 회피)에 기종별로 모았다. 기종별 획득(전부 읽기 전용):
- PostgreSQL: EXPLAIN (GENERIC_PLAN) — 기존 경로 위임(동작 불변)
- MySQL: performance_schema digest의 QUERY_SAMPLE_TEXT(리터럴 샘플)를 EXPLAIN FORMAT=JSON (Datadog DBM 방식).
  절단(max_sql_text_length 1024B) 시 EXPLAIN 문법오류 → 스킵(정직)
- SQL Server: Query Store sys.query_store_plan의 계획 이력(캐시 축출 무관, NATIVE). actual_state
  게이트로 OFF DB는 스킵(켜는 행위 안 함), is_forced_plan은 [FORCED] 표기
- Oracle: v$sqlstats의 plan_hash_value가 곧 형태 식별자('PHV:x'). 무료 뷰(19c 라이선스 매뉴얼 —
  팩 대상은 v$active_session_history·DBA_HIST뿐)
- MongoDB: system.profile 샘플 명령을 explain(queryPlanner) 재실행, 세션 메타 필드 제거

실측(실 8081) — 정직한 범위 표기:
- **MySQL planShapeForDigest 라이브 작동 확인**: 회귀가 뜬 products 쿼리(code=?)에 대해 트래커가
  QUERY_SAMPLE_TEXT를 EXPLAIN해 shape `ALL(products)`를 실제로 저장(09:44:56). 우리 자체 조회 쿼리도
  회귀 시 `ALL(events_statements_summary_by_digest)`로 플랜추적됨 — MySQL 샘플→EXPLAIN→fromMysqlJson→저장
  체인이 라이브로 동작함을 두 사례로 확인.
- **MSSQL 획득 쿼리 라이브 검증**: QS 켠 사용자 DB(dbtower_qs) 등록 후 planShapeForDigest의 획득 SQL
  (query_hash → query_store_plan → showplan XML) 직접 실행해 실제 showplan XML 반환 확인.
- Oracle: v$sqlstats에서 (sql_id, plan_hash_value) 조회 가능 확인(무료 뷰). Mongo: 프로파일러 레벨 2에서
  system.profile에 queryHash 존재 확인.
- MSSQL master(local-mssql)는 Query Store 미지원(시스템 DB) → 게이트가 empty로 스킵(정직한 동작 실측).
- **플립 감지(2-shape 비교) 로직 자체는 arc 1(56절)에서 PG로 완전 e2e 검증됨**(Index Only Scan → Seq
  Scan 플립 알림). 이번 아크의 신규분은 planShapeForDigest이고, 위와 같이 라이브 확인됨. 압축 데모에서
  단일 기종의 완전한 before/after 플립을 재현하려면 두 번의 회귀를 정확한 순서로 유발해야 하는데,
  플랜추적 트리거가 레이턴시/행수 회귀에 묶여 있어(안정 트리거는 인덱스 드랍의 rows 급증 = 한 방향만)
  압축 타임라인에선 반대 방향 기준선 확보가 까다로웠다 — 코드가 아니라 데모 부하 조건의 제약이며,
  기준선 저장(ALL(products) 실저장)까지는 라이브로 도달함.
- 단위 12건: PlanShapes 기종별 shape 정규화(PG·MySQL·MSSQL·Mongo·텍스트) — 구조 남기고 수치 버림.

## 58. 심화 아크 2차·2 — PG 복제 슬롯 감시(C-1) + 블로트 신호(C-2)

C-1 한계: pg_stat_replication은 "연결된 복제"만 보여줘, 비활성 슬롯이 WAL을 무한 보존해 디스크를
고갈시키는 PG 최빈 장애를 못 봤다. replicationSlots() PG 구현 — pg_replication_slots의 wal_status·
보존 WAL·safe_wal_size. OpsAlertDetector 규칙: lost=무효(구독자 재구축), unreserved=위험,
비활성+임계(slot-retained-mb) 초과=디스크 고갈. /replication-slots API + 복제 카드 배지.
실측(실 8081): 물리 슬롯(dbtower_demo_slot, 비활성) 생성 후 보존 WAL 119MB → API 노출 확인,
OpsAlert "비활성 복제 슬롯 ... 보존 WAL 119MB (디스크 고갈 위험)" 발화.

C-2: tableBloat() PG 구현 — pg_stat_user_tables의 n_dead_tup·last_autovacuum·n_mod_since_analyze.
BloatAdvisor(PG 전용): dead ratio 20%+ & 1만개+ 블로트 후보, ANALYZE 이후 5만+ 통계 노후. 추정치라
"삭제 근거 아닌 점검 신호"로 정직 표기. 실측: bloat_demo 대량 UPDATE(autovacuum off 데모) →
Advisor VIOLATIONS "죽은 튜플 200,000개 / 66.7% (추정치)". 단위 5건. 덤: 최초 실측에서 autovacuum이
이미 청소해 0건이었던 것 자체가 autovacuum 정상 동작의 실측이었다.

## 59. 심화 아크 3차 — p95의 정직 등급을 올리다 (B-1~B-4)

배경: 레이턴시 백분위는 "같은 지표라도 기종마다 원자료가 다르다"의 교과서 사례. 기존 라벨은 NATIVE(누적)·
COMPUTED·ESTIMATED·UNSUPPORTED 4종이었다. 이번 아크는 세 기종의 등급을 실제로 올리고, 못 올리는 기종은
정직하게 남긴다. 라벨 신설: **NATIVE_WINDOWED**(누적 히스토그램 두 스냅샷 차분 = 최근 구간, 버킷 상한 근사),
**NATIVE_HISTOGRAM**(DB 히스토그램 버킷 보간). 공용 자산: HistogramPercentile(windowDiff/bucketCeiling/
interpolate) + HistogramSnapshotStore(operator는 매 호출 새로 생성되므로 스냅샷은 싱글턴 빈에 보관).

- **B-1 MySQL — NATIVE_WINDOWED**: events_statements_histogram_by_digest(digest당 450버킷 누적)를 직전
  스냅샷과 버킷별 차분 → 누적 95% 교차 버킷의 BUCKET_TIMER_HIGH를 구간 p95로. 첫 호출/재기동/구간 무실행은
  누적 NATIVE로 폴백(정직 노트). **실측(실 8081, id 1)**: 1차 호출 "[구간 학습 중]"(스냅샷 저장) → products
  부하 → 2차 호출 7/8행 NATIVE_WINDOWED 전환. products 대비 선명: **누적 p95=0.48 → 구간 p95=0.19**(최근
  부하가 빠른 인덱스 조회라 누적보다 낮게). **라이브에서 진짜 버그 2건 발견·수정**: (1) 마지막 버킷의
  BUCKET_TIMER_HIGH가 unsigned bigint 최댓값(2^64-1) 센티넬이라 getLong()이 오버플로로 예외 → BigDecimal로
  수정. (2) 모니터 계정(dbtower_monitor)이 summary 뷰 권한만 있고 histogram 뷰 권한이 없어 조회 실패 →
  코드가 조용히 누적 폴백(정직한 열화)하던 것을 경고 로그로 관측 가능하게 + mysql-init.sql에 histogram SELECT
  권한 추가 + least-privilege 문서화. 단위: HistogramPercentileTest 10건.
- **B-2 MSSQL — UNSUPPORTED 해제 → ESTIMATED**: Query Store가 켜진 DB면 query_store_runtime_stats를
  count_executions 가중 재집계(활성 interval은 in-memory+flushed 복수 행이라 필수) → p95≈avg+1.645×stdev,
  max로 캡. 게이트: actual_state_desc가 READ_WRITE/READ_ONLY 아니면 UNSUPPORTED(ALTER DATABASE 금지).
  **실측**: master(id 3, QS 꺼짐) → UNSUPPORTED 게이트 정직 스킵. QS 켠 dbtower_qs(id 22) 등록 →
  ESTIMATED 산출(join p95=0.73, insert p95=2.74 등). 단위: MsSqlLatencyEstimateTest 5건(캡·z·stdev=0).
- **B-3 Mongo — NATIVE_HISTOGRAM(프로파일러 무관)**: serverStatus.opLatencies(reads/writes/commands별
  지수 버킷 누적)를 스냅샷 차분 후 보간. 기존 COMPUTED(system.profile 쿼리 단위)는 병행 유지. **실측(id 7)**:
  1·2차 호출로 "누적 → 최근 구간" 전환 확인. **결정적 검증 — 프로파일러 OFF(level 0, profile 0건)에서
  COMPUTED는 완전 소멸(0건)했지만 NATIVE_HISTOGRAM 인스턴스 p95는 생존**(reads 3.78 / commands 3.02).
  프로파일러가 꺼진 인스턴스에서 유일한 레이턴시 관측이 된다는 것이 이 축의 핵심 가치. (실측 중 잘못된
  자격증명으로 첫 시도가 조용히 실패한 것을 발견해 올바른 창으로 재검증 — 허위 없이 정직 확인.)
- **B-4 PG — "있으면 승격" 게이트 + Oracle UNSUPPORTED 유지**: pg_extension에 pg_stat_monitor가 있으면
  resp_calls 버킷 보간(NATIVE_HISTOGRAM), 없으면 기존 ESTIMATED 그대로(동작 불변). **실측(id 2)**: 데모에
  확장 미설치 → 게이트 정직 스킵, ESTIMATED 유지(HypoPG·QS와 동일 "게이트=정직한 스킵" 패턴). 승격 경로는
  단위(PostgresRespCallsTest 10건: range 파싱·요소합산·보간)로 검증. Oracle(id 8)은 v$sqlstats에 분위수·
  표준편차·히스토그램 원자료가 없어 **UNSUPPORTED 유지 — 이번 아크의 정직성 대비군**.

UI: 레이턴시 카드 배지 6종(실측누적/실측구간/히스토그램/직접계산/추정/미지원) + 범례 갱신. 스크린샷
docs/images/webui/22(MySQL 구간)·23(Mongo 히스토그램)·24(MSSQL 추정). 전체 테스트 그린(신규 30건 포함).

## 60. 심화 아크 4차 — 데드락 축 (D-1~D-3)

배경: "서로가 서로의 락을 기다려 아무도 못 나아가는" 데드락은 자체 회복(한쪽 롤백)되지만 애플리케이션
오류로 드러난다. DB는 이미 흔적을 남기므로 <b>설정 변경 0으로</b> 읽는다. 기종마다 관측 입도가 근본적으로
달라 두 갈래로 설계: MSSQL/MySQL은 recentDeadlocks()가 리포트를, PG는 개별 사건이 없어 카운터 델타로.
공용 레코드 DeadlockEvent(detectedAt·statements·victim·resource·source), DbmsOperator에 default 2종
추가(recentDeadlocks·deadlockCount). OpsAlert 데드락 규칙 + /deadlocks API + Monitoring 카드.

- **D-1 MSSQL — system_health XE (NATIVE, 설정 변경 0)**: system_health 세션이 데드락마다 남기는
  xml_deadlock_report를 읽어 victim·관여 프로세스(inputbuf SQL)·경합 리소스를 파싱. **라이브 실측에서
  설계 수정**: 조사 단계엔 "ring_buffer가 2022에서 빈 결과"라 file target으로 고정했으나, 실제 데모
  (SQL Server 2022 Linux)에선 <b>반대로 방금 발생한 데드락이 ring_buffer에만 즉시 나타나고 .xel 파일엔
  아직 flush 안 됨</b>을 확인. 그래서 <b>두 타깃을 모두 읽어 내용으로 dedup</b>하도록 확장(어느 한계에도
  최근을 안 놓침). **실측(id 3)**: dl_test에서 두 세션 크로스 락 → 1205 victim(Process 65) → /deadlocks가
  victim="spid 65", resource="dl_test.dbo.t.PK__...", 두 트랜잭션 SQL 정확 파싱. 권한 VIEW SERVER STATE.
- **D-2 MySQL — SHOW ENGINE INNODB STATUS**: 출력의 "LATEST DETECTED DEADLOCK" 섹션을 파싱(InnoDB가
  최신 1건만 보존 → 최대 1건). **실측(id 1)**: dl_demo 크로스 락 → ERROR 1213 → /deadlocks가
  victim="트랜잭션 (2) 롤백", statements 2건, resource="index PRIMARY of table `sample`.`dl_demo`" 정확.
  권한 PROCESS(mysql-init·least-privilege 반영). 새 기능=새 권한.
- **D-3 PG — pg_stat_database.deadlocks 카운터 델타**: PG는 개별 리포트가 없어(로그에만) 누적 카운터뿐.
  deadlockCount()가 현재 DB 누적값을 주고, OpsAlert가 폴 사이 델타로 "새 데드락 N건"을 알린다(첫 관측·
  카운터 감소는 알리지 않음). **실측(sample DB)**: 크로스 락 데드락 → pg_stat_database.deadlocks 0 → 1 확인.
  OpsAlert 델타/시그니처 로직은 단위 3건(첫 관측 조용·증가 시 알림·반복 억제)으로 고정.

정직 한계(공통): 세 경로 모두 롤링/최신 저장이라 "최근"만 — 과거 전수 이력은 보장하지 않는다(응답·카드에
표기). 단위 테스트 신규: MSSQL XE 파싱 8·MySQL INNODB 파싱 5·OpsAlert 데드락 3. 전체 그린.
UI: Monitoring 탭에 데드락 카드(배지=획득 방식, victim·리소스·문장). 스크린샷 webui 25(MySQL)·26(MSSQL).

## 61. 심화 아크 5차 — Phase F 스케일 제어 (5종)

배경: 인스턴스가 많아지거나 대량 장애가 나면 "관제 자체가 부하·병목"이 된다. 다섯 축으로 스케일 여력을 확보.

- **수집 병렬화**: SnapshotScheduler의 직렬 for → 고정 크기 워커 풀(dbtower.snapshot.workers=4) + 인스턴스별
  시작 지터(40ms 스텝). collect()는 이 틱의 모든 Future.get()으로 완료를 기다려 <b>ShedLock 노드 배타를
  유지</b>(병렬은 노드 안에서만). 백오프 int[] 갱신은 synchronized로 배타화. **실측**: 인스턴스 14개(여분
  scale-mysql 8개 등록) 수집이 dbtower-collect 워커 스레드로 실행, 전체 ~1.2s(60s 주기 내). 백오프 단위 4건 유지.
- **스케줄러 풀 분리**: ThreadPoolTaskScheduler 빈(dbtower.scheduler.pool-size=4)으로 @Scheduled 폴러들이
  한 스레드를 공유하지 않게(head-of-line blocking 해소, "절전 후 동반 정지" 사건 대응). **실측**: 폴러 로그
  스레드명이 기본 scheduling-1이 아니라 <b>dbtower-sched-N</b>으로 분산됨 확인.
- **알림 폭주 제어**: WebhookNotifier에 분당 상한(dbtower.alert.rate-per-minute=12) 슬라이딩 윈도우. 초과분은
  버리지 않고 억제 카운트만 세었다가 <b>다음 허용 알림에 "그동안 N건 더 발생" 한 줄로 합산</b>. 여러 폴러가
  서로 다른 스케줄러 스레드에서 부르므로 send는 synchronized. **단위 3건**(상한 내 전송·초과 억제·합산 후 리셋).
- **인스턴스별 수집 토글**: DatabaseInstance.collectionEnabled(V9, @ColumnDefault true) + PATCH
  /api/instances/{id}/collection + UI 배지 토글(수집중/격리됨). false면 SnapshotScheduler·OpsAlertDetector가
  건너뛴다(문제 인스턴스를 삭제 없이 격리). **실측**: scale-mysql-1을 격리 → 다음 주기 그 인스턴스 수집 0건,
  나머지 13개만 수집(14-1). V9 Flyway 적용 확인.
- **헬스 스코어 캐시**: ScoreService에 노드별 인메모리 캐시 + 주기 갱신(dbtower.score.refresh-ms=60000).
  조회는 캐시 반환(매 조회마다 다섯 신호 재수집·프로브 안 함). ShedLock 없음 — 노드별 캐시라 각 노드가 자기
  것을 독립 갱신(한 노드만 갱신하면 나머지는 캐시가 비어 무의미). **실측**: 연속 GET 2회가 같은 generatedAt
  반환(재계산 안 함) 확인.

정직 한계: 병렬은 노드 안에서만(노드 간은 ShedLock 그대로). 알림 합산은 다음 알림이 와야 요약이 나간다
(완전 무음이면 요약도 대기). 전체 테스트 그린(신규 단위 3건 포함). 스크린샷 webui 27(격리 토글 배지).

## 62. 하드닝 아크 — 4축 감사 → 검증된 수정 (WS-A/B/C)

배경: 심화 4개 아크 후, 동시성·자원누수 / 기종정확성·버전 / 보안 / HA·수명주기 4개 축을 병렬 감사(웹서칭
포함)해 결함을 수집하고, 코드 재검증 + OWASP·CWE·벤더 문서 대조로 FIX/SKIP을 정했다(docs/HARDENING-ROADMAP.md).
서브에이전트 3개가 파일 소유권으로 분할 구현(충돌 0). 신규 단위 28건 + 통합, 전체 그린.

- **WS-A 보안**: (A-1) XXE — 데드락 파서 2곳·PlanShapes showplan의 DocumentBuilderFactory에
  disallow-doctype-decl+SECURE_PROCESSING 추가(같은 저장소 DeepAnalyzer가 이미 올바르게 하던 패턴에 정렬,
  CWE-611). (A-2) requireSelect 스택쿼리 거부(리터럴 밖 `;` 차단, CWE-89). (A-3) 암호화 fail-closed
  (prod 프로필+키없음 기동 실패, CWE-312). (A-4) 에러 텍스트 노출 → 일반 메시지+errorId. (A-5) MSSQL
  EngineEdition=5(Azure SQL DB)면 system_health 부재를 정직 처리(오탐 방지).
- **WS-B 수명주기·동시성**: (B-1) **삭제 시 정리** — V10 FK ON DELETE CASCADE(자식 5표) + InstanceDeletedEvent
  이벤트로 인메모리 맵(히스토그램·데드락·쿨다운·백오프) evict 배선(evictInstance 데드코드 해소). (B-2)
  plan_snapshot 보존 잡. (B-3) 지터 캡(≤2s). (B-4) Future.get 예외 분리. (B-5) WebhookNotifier deliver를
  락 밖으로(폴러 동반 지연 해소). (B-6) 종료 가드. (B-7) **HikariCP 풀 2→6 설정화**(폴러 경합발 허위 백오프
  해소). (B-9) 웹훅 @everyone 멘션 차단 + 제어문자 이스케이프.
- **WS-C 기종정확성·타임존**: (C-1) MySQL slowQueries에 +MICROSECOND/1000. (C-2) latency top에 GROUP BY
  DIGEST. (C-3) PG deadlockCount 클러스터 전체 SUM. (C-4) Oracle 스키마 필터 설정화. (C-5) PHV=0 empty.
  (C-6) TimeZone UTC 고정 + hibernate.jdbc.time_zone=UTC.

**라이브 실측(실 8081, 컨테이너 재기동 후 V10 적용됨)**:
- C-1: long_query_time=0.5s에서 sub-second 슬로우쿼리 유발 → 카드가 SLEEP(0.58)=581ms, (0.75)=750ms,
  (0.6)=600.594ms 등 실측 표기(구코드는 전부 0). mysql.slow_log가 마이크로초를 저장함을 원본으로 확인
  (`00:00:00.600594`) — 감사가 우려한 "TABLE 초 절삭"과 달리 이 버전은 보존, 수정이 실제 작동. 스크린샷 webui 28.
- C-6: 기동/스냅샷 로그 시각이 `...Z`(UTC)로 표기(이전 +09:00 KST) — 노드 간 일관성 확보.
- B-1: 임시 인스턴스(del-test) 등록·수집 후 삭제 → query_snapshot 32행 → **0행**(FK CASCADE 작동).
- 단위 28건(XXE 5·requireSelect 6·WsC 6·evict 5·mention 1·cipher 5) + 통합(PlanSnapshotRetention) 그린.
- 회귀 없음: 전체 스위트 BUILD SUCCESSFUL, 기존 기능 정상.

정직 잔여: 저장 컬럼의 Instant 전환·쿨다운 메타DB 외부화·대규모 보존 배치 삭제는 범위 밖(로드맵에 문서화).

## 63. 프로덕션 아크 — Phase 0 배포 블로커 (라이선스·암호화·데이터·AI)

배경: 셀프호스트 준비도 감사(ROADMAP "프로덕션 로드맵")에서 "남이 못 쓰던 이유" P0 넷을 없앴다. 전부
배포·법적·보안 블로커라 기능은 그대로지만 없으면 배포가 성립하지 않는 것들이다.

- **라이선스**: Apache-2.0 전문을 LICENSE로, 번들 재배포 고지를 NOTICE로(mysql-connector-j=GPLv2+FOSS
  Exception, ojdbc11=Oracle Free Use Terms, mssql-jdbc=MIT, postgresql=BSD-2, mongo-driver=Apache-2.0,
  이미지 번들 CLI mysqldump/pg_dump/mongodump). 이전엔 저작권 기본값(All Rights Reserved)이라 법적으로
  아무도 쓸 수 없었다.
- **암호화 fail-closed 확대**: `SecretCipher`의 판정을 prod 전용 → **배포 프로필 집합{prod, docker}**로.
  셀프호스트는 docker 프로필로 뜨는데 예전엔 prod만 막아 이 경로가 평문 저장으로 뚫려 있었다(CWE-312).
  blank/dev/test는 유지(키 없이 뜨는 다수 @SpringBootTest 컨텍스트 부팅을 깨지 않기 위해). compose에
  `${DBTOWER_ENCRYPTION_KEY:?}`로 compose 수준 fail-fast 이중 방어. `.env.example` 키 항목 [필수] 승격.
- **커밋된 바이너리 DB 제거**: `data/dbhub.mv.db`(2.3MB, USERS/PASSWORD 테이블 든 옛 H2) git rm +
  `.gitignore`에 `data/`.
- **AI 배선**: `.dockerignore`가 docs 전체를 제외해 이미지에서 `ai-analysis-rules.md`가 빈 프롬프트였다 →
  `!docs`+`docs/*`+`!docs/ai-analysis-rules.md` 예외 + `Dockerfile`에 `COPY docs/ai-analysis-rules.md`.
  compose에 `ANTHROPIC_API_KEY` env + `.env.example` [선택] 항목.

**라이브 실측**:
- fail-closed: `SPRING_PROFILES_ACTIVE=docker` + 키 미설정으로 jar 기동 → exit 1로 거부, 로그:
  `IllegalStateException: 배포 프로필(docker)에서 DBTOWER_ENCRYPTION_KEY가 없습니다 — 인스턴스 비밀번호
  평문 저장을 막기 위해 기동을 거부합니다`. 단위 `SecretCipherProfileTest.docker_프로필에_키가_없으면_기동을_거부한다` 추가.
- AI 규칙 번들: `.dockerignore` 예외 검증 — `busybox`에 `COPY docs/ai-analysis-rules.md`가 성공(파일이
  빌드 컨텍스트에 포함됨을 확인, 이전엔 제외돼 COPY 불가였다).
- 전체 테스트 그린(신규 단위 1건 포함).

## 64. 프로덕션 아크 — Phase 1 단일노드 하드닝 (플랫폼 자신을 지킨다)

배경: Phase 0의 준비도 감사가 드러낸 패턴 — DBTower는 "대상을 향한" 기능은 갖췄으나 "플랫폼 자신을
향한" 기능이 비어 있다(대상 TLS는 있고 웹 HTTPS는 없고, 대상 원격 백업은 있고 메타 자기 백업은 없고,
로그인 감사는 있고 로그인 잠금은 없다). 그 platform-facing 절반을 채운다.

- **로그인 브루트포스 방어**: `LoginAttemptGuard`(계정별 연속 실패 카운트, 임계 초과 시 잠금) +
  `LoginLockFilter`(인증 앞에서 잠긴 계정 차단). 실패는 인증 이벤트로 세고 성공하면 리셋, 대소문자
  우회 방지. 설정 `dbtower.security.login-lock.{max-attempts:10, lock-minutes:15}`. 한계(정직):
  인메모리라 노드별 독립 — 완전 분산은 Phase 3 공유 세션과 재검토. login.html이 잠금 시 남은 시간을 표시.
- **메타 DB 자기 백업**: `MetaBackupJob`(@Scheduled+@SchedulerLock) — 이미지 번들 pg_dump로 메타 DB를
  스스로 덤프({backup.dir}/meta/), 원격 보관 켜지면 meta/ 네임스페이스로 오프사이트. 비밀번호는
  PGPASSWORD 환경변수(argv 금지). pg_dump 없거나 메타가 PG 아니면 조용히 스킵(기능 게이트).
- **TLS(리버스 프록시)**: application-docker.yml에 `forward-headers-strategy: framework` +
  세션·CSRF 쿠키 Secure 토글(`DBTOWER_COOKIE_SECURE`). 인증서 갱신을 앱에 넣지 않고 프록시 종단.
- **로깅·커뮤니티**: 스프링 부트 네이티브 롤링 파일(LOGGING_FILE_NAME 시, 50MB/14일/500MB 상한) —
  커스텀 logback의 janino 의존을 피함. CONTRIBUTING·CODE_OF_CONDUCT·이슈/PR 템플릿, README 시스템 요구사항 절.

**라이브 실측(dev 프로필, H2, 8899)**:
- 로그인 잠금: admin에 잘못된 비번으로 연속 시도 → 1~10회는 `?error`(일반 실패), **11회째는
  `?error=locked&retryAfter=899`** 로 필터가 차단(약 15분 잠금). 단위 5건(임계·리셋·대소문자 우회) + 라이브.
  잠금 화면 스크린샷: gitblog login-lock.png.
- **라이브가 테스트를 이긴 순간**: application.yml에 `dbtower.security` 블록을 새로 추가했다가 기존
  블록과 YAML 중복 키가 됐는데, 테스트는 별도 test application.yml을 써서 통과했고 **실제 jar 부팅에서만
  SnakeYAML이 duplicate key로 거부**했다(line 37/172). 기존 블록에 병합해 해소, 실 부팅 UP 확인.
- **actuator 메트릭 토큰**: `MetricsTokenFilter` — `dbtower.metrics.token` 설정 시 /actuator/prometheus에
  Bearer/`?token` 검사. 라이브: 토큰 없음/틀림 → 401, 맞음 → 200, /actuator/health는 항상 200.
  (초기 구현은 sendError가 에러 디스패치를 타 302가 됐는데, setStatus로 깨끗한 401로 교정 — 스크레이퍼 대응)
- **API 토큰 재시작 생존**: V11 `platform_setting` + `SettingStore.getOrCreate`("있으면 읽고 없으면 생성·저장").
  미설정 시 예전엔 매 기동 랜덤이라 MCP 연동이 재시작마다 깨졌다. 라이브: h2 파일 DB로 두 번 부팅해
  `/api/security/mcp-token`을 각각 조회 → **두 부팅 모두 동일 토큰(04dc1b88…)**. 단위: getOrCreate 재조회 동일값.
- Phase 1 완료 5/5. 총 단위 372건 그린.

## 65. 프로덕션 아크 — Phase 2(1) 문의에 테이블 구조 첨부 (심화 아크 2)

배경: 이 대화의 출발점 — DB팀에 문의를 보낼 때, 정작 진단의 핵심인 "참조 테이블의 컬럼·인덱스와
조인 구성"이 빠져 있었다(쿼리·플랜·규칙·AI만). 사이트 상세 패널도 마찬가지였다. 그걸 메운다.

- **참조 테이블 추출**: `ReferencedTables.from(sql)` — 주석·문자열 리터럴을 지운 뒤 FROM/JOIN 뒤 식별자를
  긁고 스키마 수식자·따옴표·대괄호를 벗긴다. best-effort이고, 실재 검증은 describeSchema 교집합이 한다
  (CTE·별칭은 자동 탈락). 단위 7건(조인·수식자·따옴표·주석/리터럴 안 from 무시).
- **요약 조립**: `ReferencedSchemaService.describe(instanceId, sql)` — 후보를 describeSchema와 교집합해
  존재하는 테이블만 컬럼(타입+NULL)·인덱스(유니크)·대략 행수(tableStats "≈")로. 없는 후보는 notFound로 정직 표기.
  스코프: 전용 describeTables(IN-조회) 대신 describeSchema 재사용(대부분 상한 안, 밖은 notFound). InquiryService가
  이를 embed·본문에 "관련 테이블 구조" 필드로 붙이되, 대상 조회 실패가 문의를 막지 않게 격리.
- **사이트 패널**: POST /api/instances/{id}/referenced-schema + 상세 패널 "관련 테이블 구조" 버튼/섹션.
  동적 값은 전부 esc() 경유(XSS 방어).

**라이브 실측(데모 PostgreSQL sample DB 등록, 실 8891)**:
- `SELECT ... FROM plan_demo p JOIN bloat_demo b ON p.id=b.id ...` → 엔드포인트가 두 테이블의 실제
  컬럼(id integer, k integer, pad text NULL 등)과 인덱스(`idx_plan_demo_k(k)`, `plan_demo_pkey[U](id)`,
  `bloat_demo_pkey[U](id)`)를 반환. 브라우저에서 상세 패널로 렌더 확인 — 스크린샷 gitblog inquiry-schema.png.
- 총 단위 379건 그린. Phase 2 잔여: 데이터 마스킹, 로그 백업 5기종 게이트·PITR 범위.

## 66. 심화 아크 3 — 테이블 상세 정보 (DDL·크기 통계·인덱스 카디널리티, 5기종)

배경: 문의/진단에 붙는 "관련 테이블 구조"(65절)의 린 요약을, 참조한 레퍼런스 화면 수준의 풀 상세로
승격한다 — CREATE TABLE 전문·기본 통계(엔진·행수·데이터/인덱스 크기·평균 행 길이·생성 시각)·인덱스
정보(타입·카디널리티). 새 능력 `DbmsOperator.tableDetail(String)`을 5기종에 얹었다.

- **데이터 모델**: `TableDetail`(engine·rowCount·dataBytes·indexBytes·avgRowBytes·createdAt·ddl·
  ddlSource·indexes·note) + `IndexDetail`(name·columns·unique·type·cardinality). "값과 출처의 정직"
  (D4a와 동일) — DDL은 NATIVE(엔진 원문)/RECONSTRUCTED(카탈로그 재구성 근사)/UNSUPPORTED로 구분,
  미확보 카디널리티는 null(지어내지 않음).
- **주입 방어**: SHOW CREATE TABLE류는 식별자를 바인딩 못 하므로 `TableDetailSupport.requireIdentifier`
  가 `^[A-Za-z0-9_$#]{1,128}$` 밖(공백·따옴표·세미콜론·백틱)을 전부 거부.
- **기종별**: MySQL=SHOW CREATE TABLE(NATIVE)+STATISTICS 카디널리티(복합 인덱스 위치별 누적→최대값),
  PostgreSQL=카탈로그 재구성(RECONSTRUCTED)+선두 컬럼 pg_stats.n_distinct 추정(음수=비율×reltuples),
  SQL Server=재구성+카디널리티 null(DBCC 비노출 정직), Oracle=DBMS_METADATA.GET_DDL(NATIVE, 함수 인자
  바인딩)+DISTINCT_KEYS(네이티브), MongoDB=컬렉션 옵션·인덱스 JSON.
- **채널**: POST /api/instances/{id}/table-detail + 상세 패널 "상세 보기" 아코디언(스키마 정보·기본
  통계·인덱스 카드, RECONSTRUCTED엔 "근사" 배지, 동적 값 esc 경유).

**라이브 실측(데모 MySQL·PG 등록, 실 8890)**:
- MySQL `users`: NATIVE DDL(SHOW CREATE TABLE 전문), engine=InnoDB, rowCount=8118,
  createdAt=2026-07-03, PRIMARY(type=BTREE, **cardinality=8118**). 이미지의 레퍼런스 화면과 동일 구성.
- PostgreSQL `plan_demo`: RECONSTRUCTED DDL(nextval 기본값·PK·실제 indexdef 재구성),
  rowCount=300000, data 42MB/index 13MB, avgRow=141, engine·createdAt=null(정직 note),
  인덱스 2개 type=btree·cardinality=300000(n_distinct 추정). **함정 실측**: n_distinct는 float4라
  pgjdbc가 Double 변환 불가 → `::float8` 캐스팅으로 해소(서브에이전트가 사전 경고한 지점).
- **주입 방어 라이브**: `{"table":"users; DROP TABLE users"}` → OperatorException("허용되지 않는
  테이블 식별자")로 거부. 단위 `TableDetailSupportTest`(세미콜론·따옴표·백틱·공백·빈문자 거부).
- 총 단위 382건 그린. MSSQL·Oracle은 데모 미기동으로 라이브 대신 구현·컴파일·단위로 확인.

**웹 콘솔 실물 스크린샷(로그인 → 상위 쿼리 → 관련 테이블 구조 → "상세 보기" 아코디언, 8890)**:

![MySQL users 테이블 상세 — NATIVE DDL·InnoDB·카디널리티 8118](images/webui/29-table-detail-mysql.png)

![PostgreSQL demo_order 테이블 상세 — FK·CHECK 포함 재구성 DDL·엔진/생성시각 정직 미제공](images/webui/30-table-detail-pg.png)

MySQL은 SHOW CREATE TABLE 원문·엔진/생성시각까지, PG는 "카탈로그 재구성" 배지와 함께 재구성 DDL을
보이고 엔진·생성시각 행은 아예 렌더하지 않는다("값과 출처의 정직"이 화면까지 관철).

**함정 1 — 단위·API가 못 잡은 프론트 버그를 스크린샷 검증이 잡음**: 위 화면을 실제로 열어 보니
전 화면이 "불러오는 중..."에서 멈춰 있었다. 콘솔 에러는 `Identifier 'fmtBytes' has already been
declared` — 아크 3에서 추가한 `const fmtBytes`가 기존 선언(app.js:50)과 중복돼 **app.js 전체가
SyntaxError로 파싱 중단**, SPA가 아무것도 렌더하지 못한 것. Java 단위 테스트도 curl API 검증도
프론트 JS 파싱을 거치지 않아 놓쳤고, 브라우저 실물 확인만이 드러냈다(YAML 중복 키가 테스트를
통과하고 기동에서 터진 Phase 1 사례와 같은 결). 수정: 중복 선언 제거, 크기 통계의 음수(-1=미확보)
"—" 표기는 `bytesOrDash` 헬퍼로 보존. `node --check`로 전체 구문 재검증.

**정확도 개선 — "카탈로그 재구성(근사)"에서 "근사"를 뗌**: 초기 PG 재구성은 컬럼·PK·인덱스만 담고
FK·CHECK·트리거·파티션을 생략해 "(근사)" 배지를 달았다. 그러나 PostgreSQL은 `pg_get_constraintdef`·
`pg_get_indexdef`라는 **자체 권위 정의 함수**(pg_dump가 쓰는 것과 동일)를 제공한다 — 이를 써서 FK·CHECK를
정확히 담고, 담지 못하는 트리거·파티션은 pg_trigger·relkind로 감지해 **실제로 있을 때만** note에 명시한다.
배지는 "카탈로그 재구성"으로 바꿔 근사라는 오해를 없앴다(RECONSTRUCTED는 단일 명령이 없다는 사실의 표기이지
부정확의 표기가 아니다). 라이브 실측(FK+CHECK 데모 테이블 `demo_order`):
```
CREATE TABLE demo_order ( ... PRIMARY KEY (id),
  CONSTRAINT demo_order_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES demo_customer(id),
  CONSTRAINT demo_order_qty_check CHECK ((qty > 0)) );
CREATE UNIQUE INDEX demo_order_pkey ...; CREATE INDEX idx_demo_order_customer ...
```
FK·CHECK가 원문 그대로 재조립됐고, idx 카디널리티는 customer_id 고유값 50(고객 50명)으로 정확 추정.
단위 `TableDetailSupportTest.테이블_제약_FK_CHECK를_본문에_넣고_콤마가_어긋나지_않는다` 추가(총 383건 그린).

**브랜딩 — 콘솔·로그인에 DBTower 파비콘 로고 적용, 중복 "DB" 마크 제거**: 헤더의 텍스트 "DB" 박스를
빼고 파비콘(favicon.svg) 아이콘 + "DBTower"로 통일. 로그인 화면은 미인증이라 favicon.svg가 로그인으로
리다이렉트돼 안 뜨던 것을 SecurityConfig permitAll에 파비콘 자산(svg·96png·apple-touch)을 추가해 해결.

![로그인 브랜드 — 파비콘 + DBTower, "DB" 박스 제거](images/webui/31-brand-login.png)

## 67. 레퍼런스 "문제 쿼리 식별" 표 컬럼 패리티 (Top Query·Slow Query·Mongo Plan)

배경: 레퍼런스 발표의 상위 SQL/슬로우 쿼리 화면과 컬럼 단위로 대조하니, 뼈대(3탭·시점 비교·증감/신규
감지·활용사례 3종)는 이미 동작하나 표의 일부 컬럼이 빠져 있었다. 데이터는 내부에 있는데 노출만 안 된
"진짜 갭" 3개를 닫았다(의도된 차이 — CPU%·AWS PI 딥링크는 exporter/Grafana 위임 — 는 그대로 둔다).

- **Top Query 기본뷰 — Call/sec·Latency(ms)·Row Examined(Avg)**: 기존 Load/Calls/Total(ms) 대신
  레퍼런스와 같은 컬럼으로. 평균 Latency=totalTimeMs/calls, 평균 Row=rowsExamined/calls는 창 없이 정확.
  Call/sec는 누적 카운터라 단일 스냅샷으로 못 내므로 `BaselineService.recentQps`(최근 두 스냅샷 배치 차분)
  로 산출, 이력 부족 시 null→"—"(지어내지 않음). QueryStatView에 avgLatencyMs·rowsExaminedAvg·callsPerSec 추가.
- **Slow Query — User@host·Lock(ms)·Rows_sent**: MySQL mysql.slow_log에서 user_host·lock_time·rows_sent를
  추가로 뽑아 SlowQuery record 확장. 다른 기종은 미확보라 null/-1→"—"(간편 생성자로 4-arg 호출부 무변경).
- **MongoDB Slow Query — Plan(IXSCAN/COLLSCAN)**: system.profile의 planSummary를 SlowQuery.planSummary로
  노출, COLLSCAN=빨강·IXSCAN=초록 배지. 인덱스 사용 여부를 표에서 바로 판별.

**라이브 실측(8890, 재기동 후)**:
- Top Query(MySQL): 컬럼 Load/Query/**Call/sec/Latency(ms)/Row Examined(Avg)**. 예 SHOW GLOBAL VARIABLES
  Load 42.79%·Call/sec 0.06·Latency 1.67ms·RowExamined 623. 스냅샷 창 밖 쿼리는 Call/sec "—".
- Slow(MySQL): User@host="root[root] @ localhost []", Query(ms)=581.15, Lock=0, Rows_sent=1, Plan="—".
- Slow(MongoDB): **COLLSCAN(빨강, docsExamined 43,000)** · **IXSCAN { k: 1 }(초록, 500/100행)** · 관리명령 "—".
  프로파일링 레벨2로 인덱스/풀스캔 쿼리를 실행해 planSummary 확보 후 확인.
- 단위 383건 그린(SlowQuery 확장·QueryStatView 변경 반영, 회귀 없음).

![Top Query — Call/sec·Latency·Row Examined(Avg)](images/webui/32-top-query-cols.png)

![MySQL Slow Query — User@host·Lock·Rows_sent](images/webui/33-slow-mysql-cols.png)

![MongoDB Slow Query — Plan(IXSCAN 초록·COLLSCAN 빨강)](images/webui/34-slow-mongo-plan.png)

## 68. 모니터링 지표 통합 — CPU·Connections 그래프 내장 + CPU 그래프 드래그

배경: 레퍼런스의 Monitoring 탭(CPU%·Connections·Query Activity)과 "CPU 그래프를 드래그해 시점을
고른다"는 흐름 중, DBTower는 그래프를 Grafana 링크로만 위임하고 드래그 그래프도 QPS뿐이었다.
exporter·Prometheus·Grafana가 이미 데모 스택에 있으므로 앱이 Prometheus HTTP API를 직접 조회해
내장했다 — Phase 5(디스크 포화 예측)가 계획했던 PrometheusClient·node_exporter 기반이 이걸로 선구현됨.

- **PrometheusClient**(insight/internal): query_range 조회. 기능 게이트 — 미설정(dbtower.prometheus.url
  빈 값)·연결 불가·비정상 응답 전부 예외 없이 빈 결과("그래프 한 장 때문에 콘솔이 죽으면 안 된다").
  단위 3건(미설정 게이트·연결 불가·URL 정규화).
- **GET /api/instances/{id}/metrics**: CPU%(node_exporter, `100 - avg(rate(node_cpu_seconds_total{
  mode="idle"}[3m]))*100` — 호스트 수준)·Connections(MySQL threads_connected · PG numbackends
  {datname=해당 DB}). 미수집·미지원(MSSQL 등 표준 exporter 부재)은 사유를 note로 — 값을 지어내지 않는다.
  PromQL 라벨 값은 따옴표·역슬래시 제거로 주입 차단.
- **Monitoring 탭 Metric 카드**: CPU(%)·Connections 라인 차트 + "전체 화면으로 보기"(Grafana) 링크.
- **시점 비교 CPU 드래그**: 드래그 차트에 QPS ↔ CPU% 토글 — 레퍼런스처럼 CPU 그래프 위에서 조회·비교
  구간을 드래그로 선택한다.
- compose에 node-exporter(19100) 추가, prometheus job `node` 수집.

**함정 — 이 작업이 드러낸 기존 타임존 스큐 버그(중요)**: 앱 JVM은 C-6에서 의도적으로 UTC 고정인데
(DbtowerApplication), 프론트가 브라우저 벽시계(KST)를 그대로 API에 보내 **활동 그래프·비교 조회가
9시간 미래의 빈 구간을 조회**하고 있었다(metrics가 빈 결과를 돌려주는 원인을 파다 발견). 수정:
(1) API로 보내는 시각은 `toApiTime`(toISOString, UTC)으로 변환 — 서버 LocalDateTime 파서가 Z를 무시하고
UTC 벽시계를 읽으므로 JVM UTC 고정과 정합, (2) API가 주는 시각은 `parseApiTime`(Z 부여)으로 진짜
instant화 — 차트 축·드래그 선택·입력 표시가 전부 브라우저 로컬로 일관. Slow Query Captured 헤더에
"(UTC)" 명기(원문 문자열은 UTC 벽시계).

**라이브 실측(8890, 데모 MySQL)**:
- Metric 카드: CPU% 실선(node_exporter 기동 직후부터), Connections 3시간(1→7 변동), x축 KST 로컬 표기.
- CPU 드래그 end-to-end: CPU% 토글 → 조회구간 드래그(12:46~12:52 KST 입력) → 비교구간 드래그(12:40~12:45)
  → 비교 조회 성공 — "호출량 +75% / 평균 레이턴시 -5% / 읽은 행수 +113% / 신규 쿼리 10개", 하이라이트
  주황·초록 2개. 입력은 KST, 전송은 UTC(03:46~03:52)로 정확 변환.
- Prometheus 실측치: mysql threads_connected=7, pg numbackends(sample)=3, CPU 2.2~85%(rate 안정화 전 포함).
- 단위 386건 그린.

![Monitoring Metric 카드 — CPU%·Connections 내장 그래프](images/webui/35-metric-card.png)

![시점 비교 — CPU 그래프 드래그 선택(조회 초록·비교 주황)](images/webui/36-compare-cpu-drag.png)

## 69. 화면 패리티 최종 전수 재검증 — 마지막 3건 마감

배경: "이미지에 있는 것이 진짜 다 됐는지"를 세부 요소(컬럼·정렬·배지·버튼·그래프) 단위로 라이브
재검증. 68절까지로 대부분 닫혔으나 정밀 대조에서 3건이 더 나와 마저 닫았다.

- **비교뷰 Load 증감 컬럼**(레퍼런스 비교 화면의 첫 컬럼): 비교뷰가 QPS·Latency·Rows/call만 보여주고
  Load가 없었다. Load(시간 점유율%) = qps×avgMs / Σ(qps×avgMs)를 구간별로 프론트에서 계산해
  첫 컬럼에 증감(deltaCell)으로 표시, 정렬도 target QPS → target Load 내림차순으로 교체(부하 랭킹).
  백엔드 무변경 — QueryDiff의 qps·avgMs가 재료로 충분.
- **Monitoring 탭 Query Activity(QPS) 그래프**(레퍼런스 Metric 화면 하단): CPU·Connections만 있고
  QPS 병치가 없었다. 활동 스냅샷 차분 데이터를 drawSimpleChart로 병치(초록).
- **MongoDB 집계표 Plan 컬럼**(레퍼런스 Mongo 화면은 쿼리별 집계 + Plan): Plan이 이벤트 단위
  슬로우 표에만 있고 집계(Top Query)에 없었다. QueryStat에 plan 필드 추가(간편 생성자로 타 기종
  호출부 무변경 — Mongo만 채움, profiler가 계획 요약을 저장하는 유일한 소스), Mongo 집계에
  planSummary max 누산(BSON에서 null<문자열이라 그룹에 하나라도 있으면 그 값 — 대표값이지 최근값
  아님을 주석에 명시). Top Query 표는 plan 값이 있을 때만 Plan 컬럼을 그린다(타 기종 UI 무변화).

**라이브 최종 전수(8890, 이미지 11장 대응)**:
- 3탭(Top/Slow/Monitoring) · Top Query 컬럼 Load/Call/sec/Latency/RowExamined(Avg) · **Load 내림차순
  정렬 확인**(모든 행 검사) — 이미지 1 일치.
- Slow: Captured(UTC)/User@host/Query(ms)/Lock(ms)/Rows_sent/Rows_examined/Plan/Query — 이미지 2 대응
  (초 단위 대신 ms, KST 표시는 심화 아크 4 소소 잔여).
- Monitoring Metric 카드: CPU%(빨강)·Connections(파랑)·Query Activity QPS(초록) 3그래프 + Grafana
  전체화면 링크 — 이미지 3 구성 일치.
- Mongo Top Query: 집계 + Plan 배지(COLLSCAN 빨강 43,000행·21,500행, IXSCAN{k:1} 초록, 관리명령 "—"),
  Call/sec는 스냅샷 창 밖이라 "—" 정직 표기 — 이미지 5 일치.
- 비교뷰: **Load 첫 컬럼 증감**(33.29 ▲30.99 · 16.42 ▼74.38), QPS·Latency·Rows/call 증감(▲빨강·▼파랑),
  NEW 뱃지 + 분홍 하이라이트 2행, 요약 스트립, CPU 그래프 드래그 → 하이라이트 2개 → 비교 조회
  end-to-end — 이미지 6·7·9·10·11 일치.
- 남은 것(로드맵 심화 아크 4 명세): 인스턴스 Slack/담당팀 라벨 + console_url 딥링크(이미지 9~11
  좌상단 메타), Slow KST 표시 옵션, Mongo 장기 조회 샘플링. 총 단위 386건 그린.

![비교뷰 — Load 증감 첫 컬럼 + NEW 하이라이트](images/webui/37-compare-load-col.png)

![MongoDB Top Query — 집계 + Plan 배지](images/webui/38-mongo-top-plan.png)

## 70. 데이터 마스킹 배선 — 외부로 나가는 SQL의 리터럴만 가린다 (Phase 2 완결)

배경: 레퍼런스 MCP 보안 3단계의 3(데이터 마스킹) 대응. 진단력은 구조(어느 컬럼·인덱스)에 있고
민감정보는 리터럴(`WHERE email='hong@x.com'`)에 있으므로, 리터럴만 ?로 치환하고 식별자·구조는
보존한다. 정규식이 아니라 문자 스캐너인 이유: 문자열 '...'(가림)과 따옴표 식별자 "..."/`...`/[...]
(보존)의 구분, ''·\' 이스케이프, $1 플레이스홀더 보존, 숫자가 식별자 꼬리(col1)인지 리터럴(=100)
인지의 구분은 정규식으로 정확히 못 잡는다.

- **QueryMasker**(analysis 루트 — alert·insight·mcp가 공유): apply(enabled 게이트, 기본 true),
  applyForAiPrompt(enabled && mask-ai-prompt, 기본 false — 리터럴을 가리면 IN절 개수·상수 분포
  판정 정확도가 떨어지는 트레이드오프라 명시적 선택).
- **배선 4곳**: RegressionDetector(d.queryText — 웹훅·AI 프롬프트 공통 상류. MySQL/PG 정규화
  텍스트에는 멱등, Oracle V$SQL 원문·Mongo 명령 JSON이 실보호 대상), InquiryService(파싱은 원문
  으로 FROM·JOIN 추출, 발신 렌더링은 마스킹본 — 문의 SQL은 사용자가 친 원문이라 대표 경로),
  InsightController /ai-analysis(applyForAiPrompt), DiagnosisService(MCP 에코 — arguments.sql만
  마스킹, 도구 실행은 원문(EXPLAIN은 ?를 실행 못 함), 플랜 결과는 안 가림 — rows·cost가 진단의
  본체라 리터럴 마스킹이 플랜을 훼손하는 트레이드오프를 주석으로 명시).
- **함정 — 달러 인용 태그 스캔**: $tag$...$tag$의 태그 문자에 $를 포함시키면 닫는 $까지 소비해
  태그 종료를 영영 못 만난다(단위 테스트가 잡음). 태그는 [A-Za-z0-9_]만.

검증: QueryMaskerTest 7건 — 문자열/숫자만 가림, 식별자 꼬리 숫자·따옴표/대괄호 식별자 보존,
? · $1 멱등(정규화 텍스트 재마스킹 무변화), ''·\' 이스케이프, 달러 인용·16진·지수, 주석 원문 유지,
인젝션형 문자열 통째 마스킹, 토글 3조합. InquiryServiceTest가 발신 메시지에서 "user_id = 42"가
"user_id = ?"로 가려짐을 검증(원문 부재 단언 포함). 총 단위 393건 그린.

## 71. 심화 아크 4·5 구현 완결 — digest 위생·PS 사각·팀 라벨·진단 딥링크·metrics MCP

명세(로드맵 심화 아크 4 남은 조각 + 아크 5)를 권장 순서대로 전부 구현. 데이터 마스킹은 70절.

- **통계 수집 건강(digest 위생·PS 사각)**: 새 능력 `DbmsOperator.statsHealth()`(MySQL·PG, 타 기종
  unsupported 정직) + `StatsCollectionAdvisor`. MySQL은 digest 포화율(≥80% WARNING — 레퍼런스의
  자동 truncate 기준을 차용하되 우리는 경보+명령 안내만)·`Performance_schema_digest_lost`>0
  CRITICAL(신규 쿼리 감지 무력화 명시)·PS 익명 부하(실행 1만회↑ WARNING/이하 INFO,
  prepared_statements_instances 원문 보존 안내). PG는 `pg_stat_statements_info.dealloc`>0
  WARNING(evict — 저빈도 쿼리 비교 신뢰도 저하)·사용률 80% INFO. 기존 DigestsSaturationAdvisor
  (설정 위험)와 짝 — 이쪽은 실측.
- **인스턴스 팀 라벨 + 콘솔 딥링크(V12)**: `team_label`(Phase 3 LBAC와 공유할 컬럼)·`console_url`
  선택 필드. consoleUrl은 화면 href로 들어가므로 **http(s)만 허용**(@Pattern — javascript: 스킴 주입
  차단, 프론트도 이중 검증). 카드에 팀 배지·"콘솔 ↗" 링크, 회귀 알림·문의 embed에 "담당" 표기.
- **알림 → 진단 딥링크**: `dbtower.base-url` 설정 시 회귀 알림 끝에
  `{base}/?instance={id}&diagnose={질문}` 링크. 콘솔은 파라미터를 읽어 해당 인스턴스 선택 +
  Monitoring 탭 + 자연어 진단 질문 프리필(자동 실행은 안 함 — 실행은 사람이).
- **metrics MCP 도구**: CPU%·Connections 시계열을 14번째 도구로 노출 + 자연어 진단 화이트리스트
  등록 — AI가 "그 시각 CPU가 실제로 높았나"를 스스로 확인.

**라이브 실측(8890, V12 자동 적용 확인)**:
- 팀 라벨: PUT upsert(teamLabel=@db-oncall, consoleUrl=Grafana) → 카드에 배지·링크 렌더.
  `"consoleUrl":"javascript:alert(1)"` → **400 거부** 실측.
- PS 사각 감지: 세션 유지 PREPARE+EXECUTE 3회 → Advisor가
  "Prepared Statement 1개 사용 중 (실행 3회)" INFO 실측. 세션 종료 후엔 OK(정직 — PS는 세션 로컬).
- 진단 딥링크: `/?instance=1&diagnose=...` → 인스턴스 1 선택·Monitoring 탭·질문 프리필 전부 확인.
- MCP tools/list → **14종**(metrics 포함) 실측.
- 총 단위 398건 그린(신규: QueryMaskerTest 7·StatsCollectionAdvisorTest 5, 갱신: MCP 14종·upsert 시그니처).

![인스턴스 카드 — 팀 배지 + 콘솔 딥링크](images/webui/39-instance-team-badge.png)

![진단 딥링크 — 질문 프리필](images/webui/40-diagnose-deeplink.png)

## 72. Phase 2 완결 — 로그 백업 5기종 게이트 + PITR (복원 가능 창·복원 명령 안내·실제 시점 복원 e2e)

배경: 로그 백업이 MSSQL만 실동작이었고, 다른 기종의 LOG 요청은 예외가 FAILED로 뭉개져
"기종이 못 하는 것"과 "하다가 깨진 것"이 구분되지 않았다. PITR은 이력에 타입(FULL/LOG) 기록이
없어 복원 가능 범위 계산 자체가 불가능했다.

- **UNSUPPORTED 상태 신설(V13)**: BackupRun.Status 3값(SUCCESS/FAILED/UNSUPPORTED) —
  UnsupportedOperationException은 사유와 함께 UNSUPPORTED로 구분 기록. backup_type 컬럼 추가
  (V13 이전 이력은 null 유지 — FULL이었다고 위장하지 않고 PITR 계산에서 제외).
- **MySQL 로그 백업**: 게이트(@@log_bin OFF → UNSUPPORTED "대상 설정이라 켜지 않는다") →
  FLUSH BINARY LOGS(닫힌 파일 경계 확보 — 쓰는 중 파일 수집 방지) → SHOW BINARY LOGS의 마지막-1
  파일 수집. 수집 명령은 접두부 템플릿(mysql-binlog-command) + Operator가 파일명(basename)만
  덧붙임 — 데모는 docker exec -w <binlog dir> cat(mysql:8.4 이미지에 mysqlbinlog 부재 실측),
  docker 프로필은 mysqlbinlog --read-from-remote-server(MYSQL_PWD 지원 — argv 금지 규칙 호환).
- **MongoDB 로그 백업**: replicationState()(replSetGetStatus) 재사용 게이트 — standalone이면
  UNSUPPORTED(replSet 전환 안내 포함). 복제셋이면 local.oplog.rs 덤프(--oplog는 --db와 함께 못
  쓰므로 컬렉션 직접 덤프, 비밀번호는 기존 stdin YAML 방식).
- **PG·Oracle**: UNSUPPORTED 유지 + 사유 정직화 — PG는 WAL 수집이 대상 서버 구성
  (archive_command·pg_receivewal 권한/슬롯)을 요구해 "대상을 바꾸지 않는다" 원칙상 자동화하지
  않음을 명시. Oracle은 RMAN 영역.
- **PITR**: GET /api/instances/{id}/pitr-window — 마지막 성공 FULL ~ 그 이후 마지막 성공 LOG의
  창 + 기종별 복원 명령 문안(DbmsOperator.pitrRestoreGuide — 기종 분기를 서비스에 두지 않는
  원칙, MySQL=binlog --stop-datetime 재생·MSSQL=NORECOVERY 체인+STOPAT). 생성·안내만, 실행은
  사람(gh-ost·digest TRUNCATE 안내와 같은 모델).

**라이브 실측(8890, V13 자동 적용)**:
- MySQL LOG: SUCCESS — binlog.000008/000009 실파일 수집(각 202B), backupType=LOG 기록.
  FULL(585KB)+LOG 후 pitr-window available=true·안내문(mysqlbinlog --stop-datetime 파이프) 생성.
- Mongo LOG: **UNSUPPORTED**(standalone 사유) — FAILED가 아닌 구분 상태로 이력 기록 실측.
- PG·Oracle LOG: UNSUPPORTED 정직 사유. MSSQL master는 "master는 로그 백업 불가" FAILED(서버
  제약 그대로 노출 — 사용자 DB가 필요하다는 정확한 신호).
- **MSSQL 시점 복원 e2e(스펙 검증 기준 그대로)**: FULL 복구 모델 pitrdemo(3행) → FULL 백업 →
  +2행 → LOG#1 → +1행(600) → LOG#2 → pitr-window(창·logCount=2·STOPAT 안내문) → **생성된
  안내문을 실제 실행**(RESTORE 362p → LOG 5p → LOG STOPAT 3p) → 임시 DB 검증:
  **total=5, amount=600 없음**(목표 시점 10:13:55.0 = 600 삽입 전 상태 정확 재현), 원본은 6행.
- **함정(e2e가 잡음)**: 같은 서버 복원은 데이터 파일 충돌 — MOVE 절 필요. 안내문에 주석으로 반영.
- 단위 401건 그린(신규: PitrRestoreGuideTest 3 — 체인 순서·STOPAT·(server) 접두사 제거·미지원 정직).

## 73. 로그 백업 5기종 전부 SUCCESS — PG WAL·Mongo oplog·Oracle 아카이브 로그 (72절 확장)

배경: 72절에서 Mongo·PG·Oracle이 UNSUPPORTED 게이트로 남았는데, 정석 방법을 웹으로 재확인한 결과
셋 다 원칙(대상 서버 구성을 플랫폼이 바꾸지 않는다) 안에서 실동작이 가능했다. 게이트는 유지하되
게이트가 열려 있으면 실제로 수집한다 — "켜져 있으면 쓴다".

- **PostgreSQL WAL**(신규 walBackup): pg_switch_wal()로 세그먼트를 닫고(FLUSH BINARY LOGS와 같은
  결 — 함수 호출이지 서버 설정 변경이 아님) pg_walfile_name()이 주는 직전 세그먼트를 수집.
  게이트 3중: wal_level=minimal(복구 재료 없음)·pg_switch_wal 권한 부족·수집 명령 미설정 →
  UNSUPPORTED. 정석 대비 위치를 주석·note에 명시 — 상시 아카이빙의 정석은 archive_command(서버
  구성) 또는 pg_receivewal(REPLICATION 권한 스트리밍, 공식 문서)이며 폴러 원샷 모델의 대응이 이 방식.
- **MongoDB oplog**: 데모 스택을 단일노드 복제셋(rs0)으로 전환(compose — auth+replSet은 keyFile
  내부 인증 필수라 바인드 파일을 컨테이너 안으로 복사해 400 권한으로 부트스트랩, macOS 마운트 권한
  우회). oplog.rs 컬렉션 덤프는 Percona·Pythian이 문서화한 정석 증분 방식.
- **Oracle 아카이브 로그**(신규 archiveLogBackup): ARCHIVELOG 모드 게이트(NOARCHIVELOG면
  UNSUPPORTED — 모드 전환은 mount 재기동이 필요한 대상 구성) → ALTER SYSTEM ARCHIVE LOG CURRENT
  best-effort → V$ARCHIVED_LOG 최신 파일 수집. 아카이브 로그 파일은 RMAN 백업셋과 달리 완결된
  리두 파일 그 자체라 파일 복사가 유효한 보존(공식 문서 인정 방식 — 단 RMAN의 블록 검증·증분은
  포기하는 트레이드오프 명시).
- **실측이 잡은 아키텍처 함정(ORA-65040)**: PDB 접속으로는 ARCHIVE LOG CURRENT가 원천 불가 —
  아카이브 전환은 CDB 수준 작업. 처음엔 권한 게이트(UNSUPPORTED)로 설계했다가, "경계를 지금 못
  만들 뿐 이미 아카이브된 파일 수집은 가능"이 사실이라 best-effort로 강등(운영 DB는 로그 스위치가
  자연 발생). PITR 안내: PG는 pg_dump 논리 덤프에 WAL을 재생할 수 없다는 한계를 문안 서두에 명시
  (물리 pg_basebackup + recovery_target_time 절차 안내), Mongo는 --oplogReplay + --oplogLimit(ts:ordinal).

**라이브 실측(8890) — 5/5**:
| 기종 | 결과 | 산출물 |
|---|---|---|
| MySQL | SUCCESS | binlog.000010 (202B) |
| PostgreSQL | SUCCESS | WAL 세그먼트 000000010000000000000050 (16MB) |
| SQL Server | SUCCESS | BACKUP LOG .bak (서버 측) |
| MongoDB | SUCCESS | oplog.rs 아카이브 (7.5KB — replSet 전환 후) |
| Oracle | SUCCESS | arch1_586_1234644671.dbf (2.75MB — ARCHIVELOG 전환·CDB 스위치는 데모 관리자(사람) 행위) |

게이트 실측도 보존: Mongo standalone(전환 전)·Oracle NOARCHIVELOG(전환 전)·PDB ORA-65040·
ALTER SYSTEM 권한 부족(ORA-01031) 전부 UNSUPPORTED 사유로 확인됨(72·73절 과정 기록).
단위 402건 그린(PG 안내 한계 명시·Oracle 미지원 안내 테스트 갱신).

## 74. 로그 백업 정석 검증 — 체인 보충 수집(멱등)과 연속성

배경: "현업 정석대로인가"를 문헌으로 재검증한 결과, 최신 파일 하나만 수집하던 73절 구현에
**체인 구멍** 위험이 있었다 — 주기 사이에 로그가 여러 번 회전(max_binlog_size·로그 스위치)하면
중간 파일들이 조용히 빠진다. 정석(MySQL 공식 문서·현업 스크립트)은 "FLUSH 후 **마지막(쓰는 중)
이전 전부**를 복사"다.

- **세 기종 공통 수정**: 미수집 닫힌 로그를 전부 보충 수집(catch-up). 이미 수집한 파일은 로컬
  산출물 파일명 접미로 판정해 건너뛴다(멱등 — 상태 저장 없이 파일시스템이 장부). 서버는 건드리지
  않는다(PURGE/삭제는 우리 몫이 아님). location은 순수 경로 유지(원격 업로드·검증이 사용).
- **MySQL**: SHOW BINARY LOGS의 마지막 이전 전부. **PostgreSQL**: pg_ls_waldir()로 방금 닫힌 것
  이하 세그먼트 전부(권한 부족 시 방금 닫힌 것만으로 강등). **Oracle**: v$archived_log 시간순
  전부(첫 실행 폭주 방지 상한 50).
- **정직한 한계(문헌 명시)**: PG 원샷 수집은 서버와 조율이 없어 재활용 경합 창이 남는다 —
  무결 연속 아카이브의 정석은 archive_command(서버가 아카이브 확인 후에만 재활용)/pg_receivewal
  (스트리밍)이며, 세그먼트 이름이 순차라 수집본만으로 갭 검출이 가능함을 주석·note에 남겼다.

**라이브 실측(8890)** — 주기 사이 다중 회전 시뮬레이션(수동 FLUSH/switch/ARCHIVE 2회) 후
LOG 백업 1회 실행:
- MySQL: 산출물 3→13개 — 한 실행이 과거 미수집 binlog 전부(000004~000013)를 보충.
- PostgreSQL: 1→5개 — 세그먼트 **50·51·52·53·54 연속(갭 0)** 눈으로 검증.
- Oracle: 1→3개 — 스위치 2회분 보충.
- 재실행 시 "전부 이미 수집됨" 멱등 확인. 단위 402건 그린.

출처: MySQL 공식 "Using mysqlbinlog to Back Up Binary Log Files"·Server Log Maintenance,
SqlBak(FLUSH 후 마지막 이전 전부 복사), PostgreSQL 공식 Continuous Archiving(아카이브는 완전하고
연속이어야 하며 archive_command가 재활용과 조율), Percona·Pythian(Mongo oplog 증분·oplogLimit),
Oracle 공식 Managing Archived Redo Log Files(수동 아카이브·O/S 복사).

## 75. 물리 백업(PHYSICAL) 타입 — PG pg_basebackup 실복원 e2e·Oracle RMAN 정석 경로

배경: 74절까지의 정직한 한계 중 핵심 — "논리 덤프에는 로그를 재생할 수 없다"(PG·Oracle) — 를
해소하는 마지막 조각. BackupType에 PHYSICAL(물리 전체)을 신설했다. 물리 백업이 시점 복구 체인의
진짜 앵커다. MySQL·Mongo는 논리 FULL + 로그 재생이 공식 PITR 절차라 PHYSICAL을 정직하게
UNSUPPORTED(XtraBackup/스냅샷 영역)로 안내한다. MSSQL의 .bak은 이미 물리다.

- **PG PHYSICAL = pg_basebackup**: replication 프로토콜 클라이언트라 서버 설정 변경이 없다
  (wal_level=replica·max_wal_senders 기본값 충분, REPLICATION 권한만). tar 포맷(-Ft)을 stdout
  (-D -)으로 받아 기존 수집 모델 그대로 저장. -X none — WAL은 세그먼트 수집과 조합이 체인 정석.
- **Oracle 정석 경로 = RMAN**: oracle-rman-command 지정 시 LOG는 BACKUP ARCHIVELOG ALL
  **NOT BACKED UP 1 TIMES**(멱등을 RMAN 컨트롤파일 카탈로그가 보장 — 블록 검증 포함),
  PHYSICAL은 BACKUP DATABASE PLUS ARCHIVELOG. 접속·스크립트는 stdin(비밀번호 argv 금지 호환).
  **데모 Oracle Free(26ai) 이미지에는 rman 바이너리가 없음을 실측** — 미설정 시 파일 수집
  폴백(LOG)/UNSUPPORTED 사유(PHYSICAL)로 정직 처리.
- **pitr-window 앵커 확장**: PHYSICAL과 FULL 중 더 최근 성공을 앵커로. PITR 안내는 앵커 종류
  (파일명 규약)로 분기 — 논리 앵커면 한계 명시+PHYSICAL 권고, 물리 앵커면 공식 절차.

**PG 실제 시점 복원 e2e(8890)** — MSSQL STOPAT e2e(72절)의 PG판:
- 시나리오: PHYSICAL(404MB tar) → 마커 A 삽입 → LOG → **목표 시점 기록** → 마커 B 삽입 → LOG.
- pitr-window: 물리 앵커 인식·공식 절차 문안·logCount=2 확인.
- 안내문 실제 실행: tar 해체 → WAL 배치 → recovery.signal + recovery_target_time → postgres:16
  임시 컨테이너 기동. **함정 실측 2건**: (1) recovery 모드는 pg_wal 직접 배치가 아니라
  restore_command가 필수("must specify restore_command" FATAL) — wal_archive 디렉터리 +
  restore_command='cp .../%f %p' 모델로 문안 수정. (2) backup_label의 START WAL(세그먼트 58)이
  요구하는 체인을 74절의 멱등 보충 수집이 이미 확보하고 있었다(58·59·5A 보유).
- **결과**: 서버 로그 "recovery stopping before commit ... 11:23:38.887"(마커 B 삽입 직전 정지),
  복원본 SELECT → **A만 존재**, 원본 → A·B. 목표 시점(11:23:37.77) 상태 정확 재현.
- 단위 402건 그린(PG·Oracle 안내 앵커 분기 테스트로 갱신).

이로써 5기종 로그 백업 판정: MySQL·MSSQL·Mongo = 정석 그대로, **PG = 물리 앵커+WAL+실복원까지
정석 체인 완성**(연속성 무결 보장 옵션 pg_receivewal 스트리밍만 별도 모듈 잔여),
**Oracle = RMAN 정석 경로 구현**(데모는 rman 부재로 파일 수집 폴백 — 공식 인정 방식).

## 76. 백업/PITR 웹 콘솔 카드 + RMAN 스크립트 주입 방어

- **웹 콘솔 백업 카드**(Monitoring 탭): 그동안 백업은 API/폴러만 있고 UI가 신선도 뷰뿐이었다.
  복원 가능 창(FULL/PHYSICAL 앵커 ~ 마지막 LOG)·복원 명령 문안(접이식)·이력 표(시각·타입·상태·
  검증·산출물)를 한 카드로. 상태 3값을 색으로 구분(SUCCESS 초록·FAILED 빨강·UNSUPPORTED 회색 —
  "못 하는 것"을 실패로 위장하지 않는다). 라이브(데모 PG): PHYSICAL 앵커 인식·공식 PITR 절차 문안·
  이력 8건(PHYSICAL/LOG/UNSUPPORTED/VERIFIED 혼재) 렌더 확인.
- **RMAN 스크립트 주입 방어**(커밋 보안 리뷰 반영): rmanBackup은 접속 자격증명을 RMAN stdin
  스크립트의 CONNECT 한 줄에 넣는데, username/password에 개행이 섞이면 다음 줄이 임의 RMAN
  명령으로 실행된다(예: password="pw\nSHUTDOWN IMMEDIATE;"). 스크립트 조립 전에 개행·CR·NUL·
  큰따옴표를 거부(조용히 제거해 인증을 어긋나게 하지 않고 명확히 실패). 단위 2건(개행 password·
  따옴표 username 거부). 총 404건 그린.

![백업/PITR 카드 — 복원 가능 창·문안·이력(PHYSICAL/LOG/UNSUPPORTED 색 구분)](images/webui/41-backup-pitr-card.png)

## 77. Phase 3 — 팀 스코핑(LBAC) + 공유 세션(재시작 생존)

- **공유 세션(spring-session-jdbc, V15)**: 세션을 메타 DB(SPRING_SESSION)에 저장 — 앱 재시작·다중
  노드에서 로그인이 살아남는다(ShedLock과 같은 "새 인프라 없이" 논리). 스키마 단일 권위는 Flyway라
  jdbc.initialize-schema=never + 명시 @EnableJdbcHttpSession(자동구성이 인메모리로 조용히 폴백하는
  것 방지 — 재시작 생존이 핵심 계약). **라이브 실측**: admin 로그인 → DB에 세션 저장 확인 →
  앱 kill 후 재기동 → **같은 쿠키로 /api/me 200(admin)**. (함정: 처음 store-type 미지정 시 인메모리라
  재시작 401 — store-type=jdbc 명시로 해소.)
- **팀 스코핑(LBAC, V14)**: platform_user.team_label — 사용자에 팀을 달면 그 팀 인스턴스 + 라벨 없는
  전역 인스턴스만 본다. **강제는 단일 경계** RegistryService.findAll/findById(모든 모듈이 경유).
  스코프는 authority(TEAM_라벨)로 실어 로그인 시 부여 — registry가 security를 참조하지 않고
  SecurityContext만 읽어 모듈 경계 유지. 규칙: 인증 없음(폴러)·ADMIN·라벨 없는 사용자=전역,
  팀 사용자=자기 팀+전역. **스코프 밖 단건은 403이 아니라 미등록과 같은 404 메시지**(존재 노출 방지).
  ADMIN 전용 PATCH /api/security/users/{username}/team으로 지정.
- **라이브 실측**: local-mysql→team-a, local-postgres→team-b, viewer→team-a 지정 후 viewer 로그인 →
  목록 **5개**(team-a mysql + 라벨없는 전역 4개), **team-b(postgres) 제외**(admin은 7개 전부).
  team-b 단건 id=2 → **404**, 자기 팀 id=1 → 200. 단위 TeamScopeTest 6건(네 규칙 + 스코프밖 404
  메시지 동일성). 총 단위 410건 그린.

![VIEWER(team-a) 인스턴스 목록 — team-a 뱃지 + 전역만, team-b 제외](images/webui/42-lbac-viewer-scope.png)

## 78. Phase 5 — 디스크 포화 예측 (선형 추세 ETA, 실측 CRITICAL)

- **DiskForecastAdvisor(전 기종)**: "지금 여유"가 아니라 **"이 속도면 며칠 뒤 차는가"**를 계산.
  소스는 node_exporter(호스트 지표) — 디스크는 DB가 아니라 호스트 자원이라, 인스턴스에 V16
  `node_filter`(Prometheus 라벨 셀렉터, 예: instance="db-node-3:9100")를 달아 호스트를 특정한다.
  ETA는 선형 추세 avail / (-deriv(avail[6h])): 최근 6시간 감소 속도 유지 가정을 detail에 명시
  (예측이지 사실이 아님). 임계 ETA≤3일 CRITICAL / ≤14일 WARNING, 추세 없어도 여유<10%면 WARNING.
  Prometheus 미설정·미수집이면 조용히 스킵(값을 지어내지 않는다). nodeFilter가 mountpoint를 직접
  지정하면 기본 "/"를 양보 — DB 데이터는 전용 마운트(/data 등)가 실무 정석이라 "/"만 보면 정작
  데이터 디스크를 놓친다.
- **node-exporter 정석화**: 기존 compose가 rootfs 마운트 없이 떠 있어 컨테이너 자기 자신만 보였다
  (실측: node_filesystem에 mountpoint="/" 부재). 공식 가이드대로 `/:/host:ro` + `--path.rootfs=/host`
  적용 → 5기종 볼륨이 실제로 사는 Docker VM 데이터 디스크(/dev/vda1, /var/lib, 1TB)가 보인다.
- **라이브 실측(진짜 소모로 만든 진짜 경보)**: local-mysql에 nodeFilter=mountpoint="/var/lib" 지정
  (PUT upsert로 저장·응답 왕복 확인 — V16 라이브 적용). mysql 볼륨에 256MB/15초 실쓰기 루프
  (~17MB/s)를 돌린 뒤 Prometheus 직접 조회: 여유 76.98%, ETA 72,117초(0.83일). Advisor 스윕
  → **CRITICAL "디스크 포화 임박 — 약 0.7일 내 (여유 76.8%)"** — 여유가 넉넉해도(77%) 속도가
  빠르면 치명이 맞다는 것을 화면으로 증명(잔량 경보만 있으면 이 케이스에 침묵한다). 부하 정리 후
  파일 삭제·루프 종료 확인(782G 복원).
- 단위 9건(DiskForecastAdvisorTest — ETA 3/14일 경계, 절대 여유 10%, CRITICAL 시 중복 억제,
  미수집 침묵, mountpoint 양보 셀렉터). 총 419건 그린.

![Advisors — 디스크 포화 임박 CRITICAL(선형 추세 0.7일, 여유 76.8%)](images/webui/43-disk-forecast.png)

## 79. Phase 4 — 서버 공유 인지(호스트 그룹핑) — 서버 전역 신호 dedup

- **문제(실측 가능한 중복)**: 등록 단위는 DB(host·port·dbName)라 같은 서버에 DB를 여러 개 등록하면
  (데모: local-postgres+dbtower-self가 15432, local-mssql+mssql-pitr가 11433 공유) 서버 전역 신호
  (유휴 트랜잭션·복제 지연/슬롯·데드락 — 전부 서버 단위 관측)가 **인스턴스 수만큼 중복 감지·중복
  경보**되고, 대상 서버 탐침도 그만큼 중복이었다.
- **설계**: `DatabaseInstance.serverKey()`(host 소문자+":"+port — 엔티티 추가 없이 계산 키, DNS 해석
  안 함) 파생 그룹. **dedup은 탐침·경보에만, 위험 귀속에는 안 한다** — 온디맨드 점검·헬스 스코어는
  현행(같은 서버의 두 DB가 모두 위험한 건 사실이라 점수까지 반으로 줄이면 왜곡).
  - OpsAlertDetector: 서버 전역 4종은 그룹 대표(id 최소)에서만 탐침. 공유 서버면 경보에
    "(서버 x 공유 — ... 전체에 해당)" 명시(대표 이름만 보고 남의 팀 문제로 오인 방지).
    인스턴스 스코프(스냅샷 정지·백업 신선도)는 각자 유지.
  - AdvisorSweepJob: 호스트 스코프 Advisor(`Advisor.hostScoped()`, 현재 디스크 예측)는
    호스트(+nodeFilter — 다른 마운트는 다른 점검) 그룹당 1회, 나머지는 **SHARED** 상태로 표기
    (새 AdvisorCheck.Status — 생략을 숨기지 않고 "누가 대신 점검했나"를 남긴다).
  - 웹 콘솔: 같은 host:port 카드에 "서버 공유 ×N" 배지(hover에 공유 인스턴스 목록·dedup 설명).
- **라이브 실측**: PG(15432 공유)에 진짜 idle-in-transaction 세션을 열고(BEGIN 후 방치, pid 45664)
  폴러 감지 → 경보 **1건**(local-postgres 대표)에 "(서버 127.0.0.1:15432 공유 — local-postgres,
  dbtower-self 전체에 해당)" 포함. dbtower-self·mssql-pitr는 인스턴스 스코프 신호(백업 없음·수집
  정지)만 각자 발신 — 서버 신호 중복 0. Advisor 스윕 로그 "instances=7 ... **호스트공유생략=5**"
  (호스트 그룹 2개만 실행: mysql nodeFilter 그룹 + 무필터 그룹 대표). 데모 후 세션 종료.
- 단위 6건(같은 서버 1회+공유 명시·다른 서버 각각·인스턴스 스코프 유지 / 스윕 대표만 실행·
  nodeFilter 다르면 미dedup·SHARED 표기). 총 425건 그린.

![인스턴스 카드 — 같은 host:port 페어에만 "서버 공유 ×2" 배지](images/webui/44-server-shared-badge.png)

## 80. Phase 3·4 — 관제탑이 두 대가 될 때: 수집 샤딩·무중단 페일오버·분산 잠금

- **수집 샤딩(Phase 4)**: 기존 `@SchedulerLock(name="snapshot-collect")` 단일 락은 노드를 늘려도
  수집은 한 노드만 했다(수평 확장 불가). `dbtower.snapshot.shards=N`(기본 1) 도입 — 틱마다 샤드
  s∈[0,N)별 락(`snapshot-collect-s`)을 각각 시도해, 획득한 샤드의 인스턴스(`instanceId % N == s`)만
  수집한다. 노드가 여럿이면 락 경쟁으로 샤드가 자연 분산되고, 한 노드만 살면 전 샤드를 잡는다
  (**페일오버가 설정 없이 유지**). consistent hashing 불요 — 카운터 누적 차분이라 담당 이동이
  데이터를 깨지 않는다. shards=1이면 락 이름을 기존 그대로 써 현행과 완전 동일(롤링 배포 배타 유지).
  샤드 수 변경은 전 노드 동시 적용 전제(노드마다 N이 다르면 담당 계산이 어긋남 — 주석·yml 명시).
- **2노드 라이브 실측**(8890/8891, 같은 메타 DB, shards=4):
  - 분산: A가 shard 0~3 선점 → B 기동 후 틱부터 B가 shard 0(3개), A가 1·2·3(4개) — 락 경쟁 자연 분산.
  - 세션: A에서 admin 로그인 → **같은 쿠키로 B의 /api/me 200**(LB 시뮬레이션).
  - 페일오버: A kill → 같은 쿠키로 B 200(재로그인 없음), 다음 틱에 B가 **4개 샤드 전부 인수**
    (`샤드 수집 shard=0/4·1/4·2/4·3/4` 로그, 01:40:20). Phase 3 잔여 "한 노드 kill 무중단 e2e" 충족.
- **로그인 잠금 카운터 메타 DB 이관(V17, Phase 1 정직 표기 해소)**: 인메모리 카운터는 노드별
  독립이라 LB 뒤 N노드에서 실패 허용치가 사실상 N배였고 재시작하면 잠금이 풀렸다. login_attempt
  테이블(계정 PK·실패 수·잠금 해제 시각)로 이관 — 동시 쓰기의 카운트 유실 가능성은 임계(10회)
  스케일에서 잠금 시점만 미세하게 늦출 뿐이라 락 비용 대신 감수(주석 명시). **라이브 실측**:
  임계 3으로 A·B를 오가며 실패 3회(A1·B1·A1) → **4번째 시도가 B에서 잠금**(error=locked,
  retryAfter=899s), 메타 DB에 viewer/3회/잠김 행 확인. 노드를 나눠 때려도 임계는 하나다.
- 단위: 샤딩 4건(획득 샤드만 수집·전 샤드 인수·기존 락 이름 하위 호환·무획득 무수집) +
  잠금 가드 5건(스토어 교체 후 동일 계약). 총 429건 그린.

![페일오버 후 node B(8891)가 같은 세션으로 콘솔 서빙](images/webui/45-node-b-survivor.png)
![노드를 오간 실패 3회 뒤 잠금 — 카운터가 메타 DB라 노드 분산 우회 불가](images/webui/46-cross-node-lock.png)

## 81. Phase 4 — query_snapshot 월별 파티셔닝: 보존 정리 DELETE 1,880ms → DROP 12.8ms

- **왜**: 최대 볼륨 테이블(수집기 60초×인스턴스당 100행 — 5대면 하루 72만 행)의 보존 정리가
  벌크 DELETE라 느리고, dead tuple 블로트가 남는다. **before 실측**: 합성 200만 행
  `DELETE WHERE captured_at < cutoff` = **1,879.9ms**, 이후 VACUUM을 해도 공간 미반환 —
  생존 17.3만 행인데 테이블 404MB 유지.
- **V18(신테이블→복사→스왑)**: 기존 테이블은 ALTER로 파티션 전환 불가. 월별 RANGE 파티션 +
  DEFAULT 안전망 + 기존 데이터 월 전체 커버 파티션을 DO 블록으로 생성, 복사·시퀀스 동기화·스왑을
  Flyway 단일 트랜잭션으로. PG 16은 파티션 테이블 identity 미지원(17부터) — 시퀀스 DEFAULT로 대체.
  PK는 파티션 키 포함 (id, captured_at). 라이브 적용 1.59초(17.4만 행), **부산물로 블로트도 소멸**
  (404MB → 55MB — 스왑이 곧 재작성이라). 수집(JDBC 배치 INSERT)·화면(Top Query 렌더) 모두
  파티션 위에서 무변경 정상.
- **보존 잡 = 파티션 수명주기 관리자로 확장**: (1) 이번 달·다음 달 파티션 선생성(멱등 —
  정상 INSERT가 DEFAULT로 새지 않게), (2) 월 전체가 기한 지난 파티션은 **DROP TABLE**,
  (3) 기한이 걸친 파티션 내부는 기존 DELETE(프루닝으로 그 파티션만 스캔, 남는 블로트도 다음 달
  DROP 때 함께 소멸 — 블로트 수명이 유한해짐). 파티셔닝 여부는 PG 카탈로그로 판별, 아니면(H2
  테스트·전환 전) DELETE 폴백 — 같은 계약(보존 N일)을 두 방식으로 지킨다. DROP 판정은 이름
  규약(query_snapshot_yYYYYmMM) 파싱으로 보수적으로(규약 밖 자식·DEFAULT는 안 건드림).
- **after 실측**: 6월 파티션 생성 → 합성 200만 행 적재 → `DROP TABLE query_snapshot_y2026m06`
  = **12.8ms** (DELETE 대비 **147배**, 블로트 0). **프루닝 실측**: 7월 하루 범위 EXPLAIN이
  `query_snapshot_y2026m07` 하나만 스캔(6월 200만 행 무접촉).
- health_sample은 보류(정직) — 볼륨이 훨씬 작고(헬스 폴 주기당 1행 vs 100행) 아직 전용 보존
  잡도 없어, 파티션 관리 비용 대비 실익이 없다. 볼륨 실측이 정당화할 때 같은 패턴 적용.
- 단위 4건(월 전체 경과만 DROP·경계 동치·규약 밖 자식 보호·생성 DDL-판정 규약 쌍). 총 433건 그린.

![파티션 전환 후 Top Query 정상 렌더 — 화면·수집 무변경](images/webui/47-partitioned-topquery.png)

## 82. Phase 4 — 커넥션 온디맨드: 격리한 대상에서 커넥션 0으로

- **문제(실측)**: 격리(collectionEnabled=false)한 local-mysql에 3분 뒤에도 앱 커넥션 1개가 남아
  있었고(minimumIdle=1 — 영구 유지), processlist의 Sleep time이 계속 리셋됐다 — **SLO 헬스 폴러가
  격리를 무시하고 60초마다 핑**하고 있었다(격리의 목적이 "문제 대상을 두드리지 않기"인데).
  관제 대상이 수백 대면 대상마다 유휴 커넥션 1개 = 서비스가 써야 할 슬롯을 관제가 상시 점유.
- **변경**: (1) minimumIdle=0 — 유휴 하한 제거, 활발한 대상은 idleTimeout 안에서 따뜻하게 유지.
  (2) idleTimeout·maxLifetime 명시 + **하한 가드**: idleTimeout < 수집 주기면 활발한 대상도 틱마다
  물리 재연결(풀의 존재 이유 소멸)이라 max(설정, 주기+30s) 강제. (3) 장기 미사용 풀 LRU 정리
  (evict-after-minutes, 기본 30분) — 유휴 커넥션은 idleTimeout이 말리지만 풀 객체(하우스키핑
  스레드·메모리)는 남으므로 통째로 close, 다음 사용 시 재생성. (4) SLO 헬스 폴러에 격리 스킵 —
  격리 기간의 가용성 이력은 공백(관제에서 뺀 기간 — down으로 지어내지 않음).
- **라이브 실측(데모: evict 2분)**: 격리 해제 → 수집 재개, 앱 커넥션 1(활성 유지 — 수집 지연 회귀
  없음) → 격리 → 2분+스윕 후 "유휴 풀 정리 — pool=dbtower-local-mysql" 로그, **앱 커넥션 0** →
  격리 해제 → 다음 틱 풀 재생성·수집 재개 정상. before 1개 영구 / after 0 수렴.
- 단위 3건(하한 가드 — 짧으면 상향·충분하면 그대로·주기 비례). 총 436건 그린.

## 83. Mongo oplog ts 증분 — 겹침 엔트리가 곧 연속의 증거

- **왜**: 로그 백업이 매번 oplog 전체를 떴다 — oplog가 크면 매 주기 같은 데이터를 반복 수집.
  증분의 위험은 구멍(직전 수집과 이번 수집 사이 유실)이라, 증거 있는 연속성이 설계의 본체다.
- **설계**: 직전 산출물 파일명의 ts 마커(-tsT_I.archive — 파일시스템이 장부, binlog 보충과 같은
  원칙) 이후만 mongodump --query {"ts":{"$gte":...}}로 덤프. **$gt가 아니라 $gte** — 직전 덤프의
  마지막 엔트리를 일부러 겹쳐 받아, 겹침 엔트리의 존재가 "체인에 구멍 없음"의 증거가 된다.
  마커는 덤프 시작 시점의 최신 ts(덤프 중 유입분은 다음 증분이 겹쳐 받는 안전 방향). oplog는
  순환(capped)이라 가장 오래된 엔트리가 마커보다 새로우면 **조용한 전체 재덤프가 아니라 명확한
  실패**(FULL 재시작 + 주기 단축 안내) — 조용히 메우면 체인 구멍이 성공으로 위장된다.
  쿼리는 공백 없는 압축 JSON(argv 분리 안전, 값은 숫자만 — 주입 면적 없음).
- **라이브 실측**: 1차 LOG = 전체 215,848바이트, 마커 ts1784170173_1 → sample에 20건 insert →
  2차 LOG = **7,686바이트(28분의 1)**, --query 적용, 새 마커 ts1784170198_21. 2차 아카이브를
  임시 네임스페이스로 실복원해 확인: **첫 엔트리 ts = 1784170173_1 (1차 마커와 정확히 일치)**,
  총 24엔트리(겹침 1 + 신규) — 겹침 실증.
- 단위 3건(마커 파싱·규약 밖 무시, $gte 압축 쿼리, 체인 구멍 판정 — 초·ordinal 경계). 총 439건 그린.

## 84. pg_receivewal 스트리밍 — WAL 무결 연속의 정석, 그리고 docker exec -i 함정

- **왜**: 풀 방식(pg_switch_wal 후 완결 세그먼트 수집)은 수집 주기 사이에 세그먼트가 재활용되면
  체인에 구멍이 날 수 있다. pg_receivewal은 복제 프로토콜 실시간 스트리밍으로 그 창을 없애고,
  복제 슬롯(--slot)은 수신자가 죽어 있는 동안의 WAL도 서버가 보존해 재시작 후 이어받게 한다.
- **설계(WalStreamManager)**: 상주 프로세스는 "실행"이 아니라 "보살핌"이 일 — 30초마다 대상 집합
  (PG + LOG 정책 enabled + 격리 아님) 계산, 죽은 스트림 재기동, 대상 제외 스트림 종료. 슬롯은
  --create-slot --if-not-exists로 멱등 선생성. 정직한 한계 명시: 슬롯 배타(노드 1곳 설정 전제,
  미설정 노드는 기능 게이트로 침묵), 미소비 슬롯의 WAL 무한 보존 위험(감시는 기존 OpsAlert 슬롯
  규칙 담당). 산출물 장부·PITR 창 계산은 풀 방식 LOG 백업의 몫(중복 계상 방지).
- **함정(실측으로 잡음)**: 첫 e2e에서 컨테이너 안 pg_receivewal을 죽였는데 재시작이 안 됐다 —
  호스트의 **docker exec -i CLI가 원격 프로세스 사망 후에도 stdin EOF를 기다리며 생존**, 매니저의
  isAlive()가 죽은 스트림을 산 것으로 오인. 해법 이중: 템플릿에서 -i 제거(pg_receivewal은 stdin
  불요) + spawn 직후 자식 stdin close(EOF 즉시 전달 — 방어). 죽은 척하는 래퍼는 생존 감시의 적이다.
- **라이브 e2e**: 기동 → 슬롯 active=t, .partial 수신 → pg_switch_wal → 완결 세그먼트 C8 →
  컨테이너 안 프로세스 kill → **30초 내 "사망 감지 — 재시작 1회차" + 재기동, 슬롯 재접속(active=t)**
  → 추가 WAL 발생 → 수신 디렉터리에 **C8, C9, CA.partial 연속**(kill 공백 구간의 C9를 슬롯이
  보존 — 재시작 사이 유실 0).
- 단위 2건(대상 판정 — PG·LOG·격리 3조건, 명령 미설정 기능 게이트). 총 441건 그린.

## 85. 잔여 소탕 1 — health_sample 파티셔닝(V19)·Slow 로컬 시간·Mongo 보존 창 표기

- **health_sample 월별 파티셔닝(V19)**: 81절 query_snapshot과 같은 패턴(신테이블→복사→스왑,
  시퀀스 DEFAULT, PK에 파티션 키). 파티션 수명주기 로직은 **PartitionLifecycle 공용 컴포넌트로
  일반화**(루트 패키지 — insight·slo가 함께 쓰는 저장 인프라, LockProvider와 같은 배치 논리) —
  두 보존 잡이 테이블 이름만 다르게 같은 계약(선생성·월 전체 경과 DROP·비파티션 DELETE 폴백)을
  쓴다. droppable 판정에 테이블 접두 검사 추가(다른 테이블 파티션 이름엔 반응 안 함).
  라이브: V19 적용 후 health_sample_y2026m07(672kB)·m08·DEFAULT 3파티션, 수집 10,066행 지속.
  성능 수치는 81절과 동일 메커니즘이라 재실측하지 않는다(정직 — 구조 검증만).
- **Slow Query 로컬 시간 표시**: 기종별 원문이 제각각(ISO·공백 구분·Mongo 원문 문자열)이라
  파싱 가능한 것만 브라우저 로컬로 변환하고 **툴팁에 UTC 원문 보존**, 못 읽는 원문은 그대로
  "(UTC)" 정직 표기 유지. 라이브(KST 브라우저): UTC 03:12:12 원문이 12:12:12로 표시 + 툴팁
  "UTC 원문: 2026-07-16 03:12:12.954586".
- **Mongo 보존 창 정직 표기**: Slow 탭 하단 note — system.profile은 순환(capped) 컬렉션이라
  컬렉션 크기만큼만 보존(로그 파일 파싱 방식과 보존 창이 다름을 숨기지 않는다).
- **쿨다운 외부화(로드맵 낡은 표기 정정)**: 조사 결과 regression·anomaly·ops-alert 쿨다운은
  이미 전부 @Value 외부화 완료 상태였다 — 하드코딩 쿨다운 0건(grep 실측). 잔여 표기만 제거.
- 총 441건 그린(파티션 판정 테스트는 공용 PartitionLifecycleTest로 이동·확장).

![Slow Query — 브라우저 로컬 시간 표시, 툴팁에 UTC 원문](images/webui/48-slow-local-time.png)

## 86. 백업 산출물 암호화 — 3-2-1-1-0의 마지막 1 (변조는 실패로, 위장 불가)

- **왜**: 백업 파일은 대상 DB 전체 데이터의 가장 농축된 유출면인데 로컬·원격 보관 모두 평문이었다.
  3-2-1-1-0의 네 번째 조각(1 = 오프라인/불변·암호화 사본)을 채운다.
- **설계**: dbtower.backup.encryption-key(base64 32B, 비밀번호 키와 별도) 설정 시 산출물 쓰기의
  단일 관문(BackupCommands.run)에서 AES-256-GCM 스트리밍 암호화 — 형식 MAGIC("DBTENC1\n")+IV(12B)+
  암호문. **파일명 불변**이라 체인 보충·ts 마커 규약이 암호화와 무관하게 동작. 복호는 복원 검증
  한 곳(BackupService — 임시 평문 생성 후 즉시 삭제, 평문 수명 최소화)과 사람의 수동 복원.
  원격 보관은 암호문 그대로 업로드(오프사이트=신뢰 경계 밖이라 그게 목적). GCM 태그 검증이 복호에
  포함 — **변조된 산출물은 조용히 오염되는 대신 명확히 실패**한다("복원해 본 백업"에 "변조 안 된
  백업"까지). 미설정=현행 평문(기능 게이트). 한계(정직): MSSQL·Oracle 서버 사이드 백업은 대상
  서버 디스크에 직접 쓰여 이 관문을 지나지 않는다(원격 업로드 시점 암호화가 후속 후보).
- **라이브 e2e**: 키 설정 기동("산출물 암호화 활성" 로그) → mysql FULL 백업 → 산출물 헤더
  **DBTENC1**, 파일 내 "CREATE TABLE" 평문 **0건** → 복원 검증 → 자동 복호 → 임시 DB 실복원
  **VERIFIED**(2객체). 부수 수정: 백업 명령 실패 시 잔재 파일 삭제(체인 보충의 "이미 수집됨"
  오판 방지).
- 단위 5건(왕복·평문 부재, 게이트, 꼬리 1비트 변조 거부, 타 키 거부, 짧은 키 기동 거부). 총 446건 그린.

## 87. MySQL 물리 백업(XtraBackup) — 현업 주 백업의 정석, 함정 두 겹과 함께

- **왜**: 현업 주 백업은 물리가 표준(논리 덤프는 SQL 재생성·인덱스 재구축 비용으로 대용량에서
  비현실적, binlog 재생 앵커도 물리+좌표가 정석). 5기종 중 MySQL만 물리 경로가 없었다.
- **구현**: PHYSICAL 타입 = XtraBackup --stream=xbstream을 stdout으로 받아 단일 파일 저장 —
  기존 암호화 관문·원격 보관과 그대로 조립된다. xtrabackup은 datadir 파일을 직접 읽어야 해서
  (접속만으로 불가) 데모는 datadir 볼륨(:ro)을 공유한 XtraBackup 컨테이너(docker run --user 0),
  프로덕션은 DB 호스트 바이너리 — 배치 형태는 접두부/args 템플릿이 흡수.
- **함정 두 겹(전부 실측)**: (1) xtrabackup은 **MYSQL_PWD를 읽지 않는다**("password: not set").
  (2) mongodump 패턴으로 --defaults-extra-file=/dev/stdin을 시도하니 defaults 로더가 **파이프를
  조용히 무시**(에러 없이 password not set — 조용한 폴백 또 하나). 해법: 비밀번호를 XB_CNF
  환경변수로 넘기고 래퍼 스크립트(코드 소유)가 컨테이너 안 임시 파일(umask 077)로 떨어뜨려
  읽는다 — 호스트·컨테이너 어느 argv에도 비밀번호 없음. cnf 내용은 제어문자 거부 + 이스케이프
  (RMAN CONNECT 방어와 동일 원칙). (3) 덤으로 uid 불일치(OS error 13, #innodb_redo) — --user 0.
- **검증 = prepare**: 물리 산출물 검증은 "파일 존재"가 아니라 격리 컨테이너에서 xbstream 추출 +
  **xtrabackup --prepare 실행**(redo 적용·미완 트랜잭션 롤백 = 크래시 복구를 실제로 수행) —
  성공하면 "복원 가능한 물리 백업"이 사실로 증명된다. 검증 명령 미설정은 UNSUPPORTED(위장 금지).
- **PITR 문안 분기**: 물리 앵커(xbstream)는 논리 적재가 아니라 xbstream -x → --prepare →
  --copy-back → **xtrabackup_binlog_info 좌표부터** binlog 재생(--start-position 없이 재생하면
  백업에 이미 든 변경이 중복 적용 — 문안에 경고 명시).
- **라이브 e2e(암호화 조립 포함)**: PHYSICAL 실행 → **81,269,868바이트 xbstream, 5.6초, SUCCESS**,
  산출물 헤더 DBTENC1(암호문) → 검증 → 자동 복호 → xbstream 추출 → prepare → **VERIFIED**.
  PITR 창 앵커가 물리 산출물로 갱신되고 물리 절차 문안 생성 확인.
- 단위 +1(물리 앵커 문안 — prepare·copy-back·binlog_info 좌표·중복 재생 경고). 총 447건 그린.

![백업/PITR 카드 — 물리(xbstream) 앵커와 복원 가능 창](images/webui/49-xtrabackup-physical.png)

## 88. Discord 봇 인바운드 — 알림 → 진단 루프의 왕복 완성 (보안 3단계)

- **왜**: 심화 아크 5의 1단계(딥링크)는 알림에서 콘솔로 "건너오게" 했다 — 2단계는 채팅에서
  슬래시 커맨드(/dbtower instance question)로 자연어 진단을 부르고 결과를 그 자리에서 받는다
  (레퍼런스의 "알럿 스레드 → AI 분석 댓글" 구도의 셀프호스트 대응). 채널 계층 원칙 유지 —
  컨트롤러는 검증 후 DiagnosisService 위임뿐.
- **보안 3단계(로드맵 설계 그대로)**: (1) **Ed25519 요청 서명** — Java 표준 EdDSA로 검증(외부
  암호 라이브러리 0), timestamp||body 서명, 불일치 401(Discord 프로토콜 요구이기도). RFC 8032
  raw 키(리틀엔디언 y + x 홀짝 비트)를 직접 디코드. (2) **채널 화이트리스트** — 기본 거부.
  (3) **유저 화이트리스트** — 기본 거부. SQL·진단이 채팅방에 노출되는 채널이라 마스킹
  (QueryMasker, 70절 배선)과 함께 노출면을 명시적으로 좁힌다. 공개키 미설정 = 404(기능 게이트,
  엔드포인트 존재 은닉). 진단은 느려서 3초 제한 안에 DEFERRED(type 5) 응답 후 별도 스레드
  진단 → 팔로업 웹훅 PATCH. 본문 2000자 경계 절단(문의 embed와 같은 규칙).
- **라이브 시뮬레이션**(로컬 생성 키쌍으로 실서명 — 실 Discord 앱 등록은 외부 계정 필요라
  서명 검증까지가 실검증 범위, 정직 표기): (1) 무서명 → **401**. (2) 유효 서명 PING →
  **{"type":1} PONG**. (3) 화이트리스트 밖 채널 → **ephemeral 거부**(flags 64). (4) 허용
  채널+유저 커맨드 → **DEFERRED** 즉시 응답 후 백그라운드에서 **AI가 도구 3연쇄 실행**
  (health → query_stats → wait_events, 각 단계 사유 로그) → 팔로업 PATCH 발사(가짜 앱 ID라
  discord.com 404 — 실등록 시 이 지점이 200이 된다).
- 단위 4건(유효 서명 통과·본문/타임스탬프 변조 거부, 타 키 서명 거부, 형식 오류 수렴,
  화이트리스트 기본 거부). 총 451건 그린.

## 89. Vault 동적 자격증명 — 유출 창을 "발각~수동 회전"에서 "TTL"로

- **왜**: 정적 모니터링 비밀번호는 유출되면 사람이 바꿀 때까지 유효하다. Vault database secrets
  engine은 접속할 때마다 수명 있는 계정을 발급하고 만료 시 DB에서 자동 소멸시킨다(하드닝 잔여).
- **설계**: 인스턴스 username을 `vault:<creds 경로>`로 등록하면(등록 password는 미사용 더미)
  접속 시점에 VaultCredentials가 실제 계정을 발급받아 풀에 공급한다. 리스 80% 시점 선제 갱신 —
  만료 계정으로 접속을 시도하는 창 자체를 없앤다. ConnectionPools가 계정 변경을 감지하면
  풀을 통째로 재생성(upsert의 접속 변경과 같은 정리 원칙), 옛 커넥션·계정은 close와 TTL이
  자연 정리. 게이트: Vault 미설정이면 vault: 인스턴스는 **명확히 실패**(접두 문자열을 계정명으로
  쓰는 조용한 오동작 방지). creds 경로는 형식 화이트리스트(URL 경로 조작 차단). 한계(정직):
  적용 범위는 JDBC 풀 — 백업 CLI 템플릿의 {user}는 등록 값 렌더라 별도 정적 백업 계정 전제.
- **라이브 e2e(실제 Vault dev + database engine)**: Vault 컨테이너에 postgresql 플러그인·역할
  (pg_monitor + SELECT, TTL 2분) 구성 → `vault:database/creds/dbtower-monitor`로 인스턴스 등록 —
  **등록 접속 검증부터 동적 계정으로 통과(201)** → 헬스 up, **pg_stat_activity에 Vault 발급
  계정(v-token-dbtower--YTZk...) 실접속 확인** → 리스 80%(96초) 뒤 수집기 접속에서 **새 계정
  발급(7Rlg...) + "회전 감지 — pool 재생성" 로그 + 대상 DB에 새 계정만 잔존**(이전 계정 소멸).
  발급 → 사용 → 선제 갱신 → 풀 교체 → 소멸의 전체 수명주기 실측.
- 단위 3건(접두 규약, 미설정 명확 실패, 경로 화이트리스트). 총 454건 그린.

## 90. 디스크 지표 소스 추상화 — Prometheus | CloudWatch (RDS)

- **왜**: 78절 디스크 예측은 Prometheus에 직결돼 있었다. RDS는 node_exporter를 붙일 호스트가
  없어 AWS/RDS FreeStorageSpace가 유일한 디스크 신호 — 로드맵 약속대로 "주입 지점 하나만 바꾸면
  되는" 소스 추상화로 분리했다.
- **설계**: `HostDiskMetrics`(configured/diskAvailPct/diskEtaSeconds) 인터페이스로 판정과 소스를
  가른다. PrometheusHostDiskMetrics(기존 셀렉터 규약 이관)·CloudWatchHostDiskMetrics(FreeStorageSpace
  최소제곱 선형 회귀 — deriv와 같은 선형 가정) 두 구현, MetricsSourceConfig가 dbtower.disk-forecast.source
  로 선택(@Bean 분기 1줄). DiskForecastAdvisor는 HostDiskMetrics 하나만 주입 — 판정(임계·문안)은
  그대로 한 곳. **값의 정직 규약**: CloudWatch는 총 용량이 메트릭에 없어 여유 %가 **null**(지어내지
  않는다) → Advisor의 evaluate가 "한 축만 있으면 그 축만 판정"하도록 확장(ETA만으로 CRITICAL 가능).
- **검증 범위(정직)**: (1) 판정 로직 — evaluate의 "여유% null + ETA만" 경로 단위 고정(CloudWatch
  축). (2) 소스 코드는 실제 AWS SDK(cloudwatch 2.46.7)에 대해 컴파일·타입 검증. (3) 라이브 e2e는
  LocalStack로 시도 — SDK 클라이언트가 엔드포인트까지 실제로 도달하는 것은 확인했으나(HTTP 왕복),
  **SDK 2.46.7의 CloudWatch JSON 프로토콜 ↔ LocalStack 3.8의 구 query 프로토콜 불일치**("Missing
  Action in request for query-protocol")로 put/get이 500 — 이는 에뮬레이터 버전 격차이지 우리 코드
  결함이 아니다. 완전한 라이브 e2e는 실 AWS/RDS 필요로 남긴다(IT 테스트는 DBTOWER_CLOUDWATCH_IT
  환경변수 게이트로 보존 — 실 AWS·호환 LocalStack에서 실행). 지어낸 통과로 위장하지 않는다.
- **보안**: 커밋 리뷰가 VaultCredentials의 권한면을 잡았다 — creds 경로가 username(ADMIN 설정)에서
  와서 토큰 ACL이 닿는 임의 시크릿을 읽을 수 있었다. **database/creds/<롤> 마운트로 봉인**(이 클래스
  용도는 DB 동적 자격증명뿐 — secret/data/... 등 거부). 단위로 고정(다른 시크릿·마운트 탈출 거부).
- 총 456건 그린(+2 게이트 스킵 IT).

## 91. MCP OAuth 2.1 인가 서버 — 브라우저 로그인으로 토큰 자동 발급 (정적 토큰 대체)

- **왜**: MCP 클라이언트(Claude)가 정적 Bearer 토큰을 손으로 넣는 대신, "서버 열면 로그인 창이
  뜨고 로그인하면 자동으로 토큰이 발급되는" 표준 흐름을 쓰게 한다. DBTower엔 이미 로그인·유저·
  세션이 있어 그 위에 OAuth 2.1 인가 서버를 얹었다 — 새 로그인 체계를 만들지 않는다.
- **구현(V20)**: RFC 9728 protected-resource 메타데이터 + RFC 8414 authorization-server 메타데이터 +
  RFC 7591 동적 클라이언트 등록 + PKCE(S256 전용) authorize + token(코드 교환·refresh 회전).
  인가 코드는 60초 일회용(메모리, HA 한계 명시), 발급 토큰은 메타 DB 영속(재시작·다중 노드 생존 —
  공유 세션과 같은 이유). 토큰 검증 시 권한은 platform_user에서 재조회(정적 토큰의 고정 ADMIN과
  달리 사용자별 역할·팀 스코프 반영). **아키텍처**: /mcp를 전용 stateless 필터 체인(@Order 1)으로
  분리 — 폼 로그인·세션·CSRF가 없어야 미인증이 깨끗한 401이 된다.
- **함정(실측)**: 처음엔 통합 체인의 엔트리포인트로 처리했더니 /mcp 미인증이 **302 로그인
  리다이렉트**로 덮였다. 두 겹: (1) 폼 로그인 체인이 /mcp까지 처리 → 전용 체인 분리, (2) 엔트리
  포인트의 `sendError(401)`가 **컨테이너 에러 디스패치를 유발해 요청이 필터를 재진입**(302로 덮임)
  → `setStatus(401)`로 교체. 최종: /mcp 미인증 = **401 + WWW-Authenticate: Bearer resource_metadata="..."**
  (MCP 클라이언트의 자동 discovery 방아쇠).
- **라이브 e2e(전체 브라우저 플로우)**: (3) DCR로 client_id 발급 → (4) authorize URL을 브라우저로
  열면 **DBTower 로그인 창 자동 표시**(webui/50) → admin 로그인 → (5) 저장된 authorize 재생 →
  code를 콜백(127.0.0.1:52100)으로 리다이렉트(state 보존) → (6) code+PKCE verifier로 **토큰 교환
  성공**(access 1시간 + refresh) → (7) **OAuth 토큰으로 /mcp tools/list 14종·health 호출 성공**.
  방어 검증: 틀린 code_verifier → invalid_grant(PKCE 실패), refresh 회전 후 옛 refresh 재사용 →
  invalid_grant, 미등록 redirect_uri → invalid_request, 원격 평문 http redirect → 등록 거부.
- 단위 2건(PKCE S256 검증, redirect_uri 화이트리스트) + 라이브 전 플로우. 총 458건 그린.

![MCP 클라이언트가 authorize를 열면 뜨는 DBTower 로그인 창 — 로그인하면 토큰이 자동 발급된다](images/webui/50-oauth-login-prompt.png)

## 92. 감지 알림 리치 embed화 + OAuth 보안 리뷰 반영

- **알림 embed화**: 회귀·운영·이상 감지 알림이 밋밋한 텍스트였는데, "DB팀 문의" 카드와 같은
  리치 embed로 통일했다. 공용 빌더 `AlertEmbeds.forDetection` — 인스턴스(이름·기종)·맥락 필드
  (회귀=구간, 이상=베이스라인)·담당 팀·감지 내용(불릿)·AI 1차 분석·진단 딥링크를 구조화 필드로.
  심각도 색: 운영 경보 빨강·회귀 앰버·이상 감지 보라(콘솔 sev 색과 정렬). Slack·미설정은 기존
  텍스트 폴백 그대로(sendEmbed의 fallbackText) — "잘 꾸미는 쪽"과 "확실히 도착하는 쪽"을 URL이 결정.
  **라이브**: 실제 웹훅으로 운영 경보 embed 발사 성공(발송 실패 로그 0).
- **OAuth 보안 리뷰 반영(커밋 자동 리뷰가 잡음)**: (1) [CRITICAL] redirect_uri **userinfo 우회** —
  `http://localhost:8080@evil.com/`이 문자열 prefix 검사를 통과해 인가 코드가 evil.com으로 샐 수
  있었다 → java.net.URI 파싱으로 스킴·호스트 구조 검증, userinfo·fragment 거부. (2) [HIGH]
  **오픈 리다이렉트** — authorize에서 client 검증 전에 redirectError로 리다이렉트 → requireClient를
  최상단으로. (3) **동의 화면 부재(auth code injection)** — GET만으로 코드를 내주면 로그인된
  사용자가 악성 authorize 링크 클릭만으로 공격자 클라이언트에 코드가 발급된다 → 명시적 승인 POST
  (CSRF 보호되는 동의 화면)로 코드 발급을 옮김. 단위 5건(userinfo/fragment 우회 케이스 포함) 추가.
- 총 459건 그린.

## 93. Discord Gateway 봇 e2e — 알림에 이모지 → 자연어 진단 답글 (특권 인텐트 없이)

레퍼런스의 "이모지/멘션으로 봇 호출" 방식을 그대로 구현. 봇 토큰만 설정하면
`java.net.http.WebSocket`으로 Gateway에 상시 연결(IDENTIFY·heartbeat·재접속)되고, 알림 메시지에
트리거 이모지(🔍/🔎)를 달면 그 알림의 대상 인스턴스를 자연어 진단해 답글로 붙인다.

- **특권 인텐트(Message Content) 회피가 핵심**: 반응 이벤트(`MESSAGE_REACTION_ADD`)에는 message_id만
  온다. 봇이 그 메시지의 embed 본문을 REST로 다시 읽어 대상 인스턴스를 알아내려면 Message Content
  특권 인텐트가 필요한데(승인·심사 부담), 대신 **발송 시점에** `AlertMessageIndex`가 message_id→
  instanceId를 미리 적재해둔다. `WebhookNotifier`가 Discord 웹훅을 `?wait=true`로 보내 응답의
  message_id를 회수해 인덱스에 기록 — 반응이 오면 인덱스 조회 한 번으로 대상이 나온다(embed 파싱은
  폴백). 특권 인텐트 0개로 동작.
- **라이브 e2e(allow-self-react 테스트 훅으로 봇 자기 반응까지 처리)**: 봇 연결(READY,
  botUser=1527277424738435174) → 매핑된 운영 경보 발사 → 봇이 웹훅 메시지에 🔍 반응(HTTP 204) →
  Gateway가 `MESSAGE_REACTION_ADD` 수신 → `shouldReact` 통과 → `AlertMessageIndex.instanceFor`로
  인스턴스 32(mssql-pitr) 해소 → `DiagnosisService`가 도구 5스텝 연쇄(compare·activity·health·
  replication·list_instances) → **채널에 답글 실제 게시**(message_reference로 원 알림에 스레드).
- **답글이 환각 대신 "데이터 없음"을 명시**: 대상 인스턴스가 데모상 DOWN이라 도구가 전부 빈 결과·
  302를 반환했는데, AI 답글이 근거 수집 5스텝을 투명하게 나열하고 "근본원인을 확정하지 못했습니다…
  수치를 지어내지 않겠습니다"로 마감. AGENTS.md의 "수치를 지어내지 않는다" 원칙이 런타임 AI 응답까지
  관통함을 실증.
- **안전 기본값**: `allow-self-react`는 `@Value` 기본 false + yml 부재 — 검증은 부팅 env로만 켰고,
  커밋된 소스는 항상 봇 자기 반응 무시. 채널·유저 화이트리스트 기본 거부, 봇 토큰 미설정 시 Gateway
  연결 자체를 안 함(게이트). 순수 판정은 `DiscordTriggerRules`로 분리해 단위 검증.
- 총 464건 그린(스킵 2 — 대상 DB 미기동 라이브 케이스).

## 94. Gateway 봇 운영 다듬기 — 진단이 하트비트를 굶기던 버그, embed 답글, 재시작 스팸 차단

93절 e2e를 여러 번 돌리며 드러난 운영 결함 셋과 형식 개선 하나. 전부 라이브로 재검증했다.

- **[버그] 진단이 하트비트를 굶긴다**: 진단과 하트비트가 같은 단일 스레드 스케줄러를 쓰고 있었다
  ("워커로 넘긴다"는 주석과 달리 같은 풀). 분 단위 진단이 스레드를 점유하면 하트비트가 못 나가
  Discord가 연결을 끊는다 — 93절 e2e 로그에서 진단마다 `연결 종료 code=1000 — 재접속`이 반복된
  실측 증거. 진단 전용 워커(`dbtower-discord-diagnosis`) 분리로 해소. **수정 후 라이브**: 5스텝
  진단(약 1분) 동안 재접속 0회.
- **[버그] 하트비트 태스크 누적**: 재접속마다 HELLO에서 `scheduleAtFixedRate`를 또 걸어 이전 연결의
  하트비트가 취소되지 않고 쌓였다(끊긴 소켓에 계속 발사) → `heartbeatTask` 보관·신규 HELLO에서 취소.
- **[스팸] 재시작 보충 스캔의 반복 안내 답글**: 인메모리 매핑은 재시작에 비므로, 보충 스캔이 옛
  반응 알림마다 "대상을 알 수 없다" 안내를 재시작할 때마다 다시 달았다(실측: 재기동 직후 옛 알림
  2건에 폴백 답글 재발). 라이브 반응(사람이 기다림)은 안내 유지, 보충 스캔(이력 순회)은 로그만
  남기고 침묵 스킵으로 분리.
- **[형식] 진단 답글 embed화**: 알림·문의는 카드인데 답글만 밋밋한 텍스트였다 — 루프의 마지막
  조각도 embed로(제목 `DBTower 진단 — {인스턴스}`, 브랜드 인디고). embed description 한도(4096자)가
  본문(2000자)보다 커 긴 진단 답변에 더 알맞다(4000자 경계 절단). 성공 발송 로그 추가(그동안
  실패만 로그라 "발송됐는지"를 채널을 봐야 알았다).
- **라이브 e2e(수정 전부 반영)**: 새 알림 발사(매핑 기록) → 봇 반응 → 시작 안내 답글(200) →
  진단 5스텝(activity→compare→query_stats→wait_events→slow_queries, 재접속 0회) → **embed 답글
  게시 확인**(REST 재조회: title `DBTower 진단 — local-mysql`, color 0x6366f1, description 1539자,
  결론은 "단정할 근거 부족" 정직 판정). 총 464건 그린.

![알림 embed에 돋보기 반응 — 진단 시작 안내까지](images/webui/51-alert-embed-reaction.png)
![봇의 진단 답글 — 근거 나열과 정직한 결론](images/webui/52-bot-diagnosis-reply.png)

## 95. 알림 매핑 영속화(V21·V22) + 슬래시 커맨드 실등록 — 재시작해도 이어지는 반응 진단

- **왜**: message_id→인스턴스 매핑이 인메모리라 재시작하면 비었다 — 재시작 전에 온 알림에 반응하면
  "대상을 알 수 없다"(실측, 94절 스팸 차단의 원인이기도). 매핑을 메타 DB로(V21: alert_message_index,
  보존 30일·record 50회마다 정리) — 공유 세션·로그인 잠금과 같은 "인메모리→메타 DB" 논리의 3번째 적용.
  기록 실패는 알림 발송을 막지 않는다(부가 기능이 본질을 못 깨게 격리).
- **재시작 생존 e2e**: 프로세스 A가 알림 3건 매핑 기록(SQL로 행 확인: 인스턴스 7·8·32) → A 종료 →
  **새 프로세스 B**가 반응 수신 → **DB에서 인스턴스 7(local-mongo)·8(local-oracle) 해소** →
  진단·embed 답글 게시. 재시작 전 매핑으로 재시작 후 진단이 성립.
- **중복 진단도 영속으로 차단(V22)**: 처리 이력이 인메모리라 재시작 후 보충 스캔이 이미 답글 단
  알림을 다시 진단했다(실측) → processed_at 컬럼(IF NOT EXISTS — 수동 선적용 멱등) + 답글 성공 시
  markProcessed. 라이브: 처리된 알림에 반응 재발화 → "반응 진단 건너뜀 — 이미 처리된 알림(영속 이력)"
  로그, 답글 0건.
- **슬래시 커맨드 실등록(88절의 시뮬레이션 → 실 Discord 승격)**: cloudflared quick tunnel로
  /api/inbound/discord를 공개하고 applications PATCH로 interactions_endpoint_url 등록 —
  **Discord가 실서명 PING을 보내 검증하므로, 등록 수락 자체가 Ed25519 검증의 실 Discord 통과 증명**.
  /dbtower 길드 커맨드 등록(instance·question 옵션, 즉시 반영). 한계(정직): quick tunnel URL은
  프로세스 수명과 같다 — 터널이 내려가면 슬래시는 실패한다(상시 운영은 고정 도메인 터널/배포 필요,
  이모지 Gateway 경로는 아웃바운드 연결이라 터널 없이도 동작). 사람 발화 e2e는 채널에서 /dbtower 실행.
- 단위 3건(매핑 왕복·덮어쓰기, 처리 이력, 보존 경계). 총 467건 그린.

## 96. 백업 정책 타입별 병행(V23) — FULL 앵커 + LOG 체인이 각자의 주기로

- **왜**: 정책이 인스턴스당 1개(unique instance_id)라 "FULL 6시간 + LOG 15분" 병행 스케줄이
  불가능했다 — 현업 정석(MSSQL 주간 FULL+일간 DIFF+15분 LOG, MySQL 일간 물리+binlog 상시,
  PG basebackup+WAL 상시)과 정면 충돌. RPO는 LOG 주기가, RTO는 FULL 최신성이 정하므로 두 주기는
  독립이어야 한다. 지금까지의 PITR e2e는 한쪽을 수동 실행으로 메워왔다(사람이 기억해야 작동하는 운영).
- **구현**: V23 — unique(instance_id) 제거(환경마다 제약 이름이 달라 pg_constraint 동적 조회로) →
  unique(instance_id, type) 복합, 정책 type CHECK에 PHYSICAL 추가(물리 백업이 정책 스케줄로도
  가능해짐 — 그동안 즉시 실행만 가능했고 CHECK가 막고 있었다). upsert 키를 (인스턴스, 타입)으로,
  GET /backup-policy는 목록 반환. 폴러는 원래 정책 목록 순회라 무변경.
- **신선도 왜곡 수정**: 신선도가 "종류 무관 최근 성공"이라 15분 LOG 성공이 몇 주째 실패 중인
  FULL을 초록으로 가렸다 → 앵커(FULL/PHYSICAL, V13 이전 타입 미상 포함) 기준으로 판정. LOG 축의
  건강은 PITR 창이 별도로 말한다.
- **라이브 e2e**: local-mysql에 FULL 360분 + LOG 1분 동시 등록(둘 다 200, 목록에 2행) → 같은 틱에
  `type=FULL`·`type=LOG` 각각 실행, 다음 분엔 LOG만(독립 주기) — 셋 다 SUCCESS 산출물 확인 →
  **신선도가 최신 성공(LOG 10:52)이 아니라 앵커(FULL 10:51) 기준**으로 FRESH 판정(왜곡 수정 실증).
  검증은 임시 ADMIN 계정으로 수행 후 삭제(사용자 계정 무접촉), 검증용 정책은 비활성화로 원복.
- 단위 +2(앵커 신선도 — LOG 성공이 STALE을 못 가림, 기존 스위트 그린 유지). 총 468건 그린.

## 97. 산출물 보존 정리 + 전 인스턴스 정석 백업 세팅 (A안)

- **산출물 보존 정리(ArtifactRetentionJob)**: 병행 정책(V23)으로 산출물이 상시 쌓이면서 필요해진
  retention 축(ClusterControl류 대응). 규칙 둘 — (1) 보존 일수(기본 14, 0 이하 게이트) 지난 파일만
  삭제, 이력(backup_run)은 사실이라 보존(위치는 남되 파일은 만료될 수 있음이 계약). (2) **같은
  스트림(타임스탬프 앞 접두 그룹)의 최신 1개는 나이 무관 보존** — oplog ts 마커·체인 보충 장부가
  최신 산출물 파일명이라, 최신본을 지우면 증분이 전체 재덤프로 퇴행하거나 보충이 중복 수집한다.
  meta/(자기 백업)는 자체 retention-count가 있어 제외. 단위 3건(스트림 최신 보존·규약 밖 이름
  각자 최신 취급·그룹 키).
- **정석 세팅(라이브)**: mysql·postgres·mongo에 FULL 360분+LOG 15분, oracle·dbtower-self에
  FULL 360분, mssql은 FULL 360분(+LOG는 아래). 첫 틱에서 9정책 실행, mssql 외 전부 SUCCESS.
- **실측으로 배운 것 둘**: (1) mssql 데모 컨테이너가 죽어 있었다(25시간 — 수집 정지·신선도 경보의
  실제 원인) → 기동 후 FULL SUCCESS. (2) **master DB는 로그 백업이 원천 불가**("Cannot back up
  the log of the master database" — SQL Server 설계, master는 SIMPLE 복구 모델) → local-mssql의
  LOG 정책은 정직하게 비활성(시스템 DB는 FULL만이 실무 표준). 사용자 DB를 대상으로 등록하면 LOG
  체인이 성립한다(mssql-pitr 데모가 그 경로였다).
- 검증은 임시 ADMIN 계정으로 수행 후 삭제. 총 471건 그린.

## 98. 레퍼런스 패리티 잔여 6건 소탕 + 발표 문서 덱 구조 재구성

60장 전수 대조 감사에서 남았던 6건을 전부 구현했다. 총 478건 그린.

- **인스턴스 검색·필터·역할 배지**: 이름·호스트 검색 + 기종·팀 필터(레퍼런스의 환경/리전 필터는
  AWS 고유라 일반화 대응), 복제 역할 배지(PRIMARY/REPLICA) 지연 조회 — 역할이 확인되는 구성에서만
  붙고 미지원은 조용히 생략.
- **시간 범위(KST) 표기**: 결과 상단 요약에 "조회하는/비교하는 시간 범위(KST)" — 단독 조회는 비교를 -로.
- **명령/행 연산 세분 차트 + Mean/Max/Min**: 기존 "Grafana 위임" 결정을 부분 재결정 — 레퍼런스
  패리티 요구로 콘솔에도 내장하되 소스는 exporter 그대로. MySQL은 Com_* rate(SELECT/INSERT/UPDATE/
  DELETE Commands), PG는 pg_stat_database tup_* rate(명령 카운터 부재의 정직한 등가), 그 외 기종은
  미지원 사유. **라이브**: MySQL SELECT 201포인트 mean 0.41/s·PG Rows returned mean 414.5/s·
  Oracle 미지원 note. 덤 수확: 검증 중 PrometheusClient의 침묵 catch에 원인 로그를 달았고,
  "빈 결과"의 원인이 앱이 아니라 테스트 입력(KST 벽시계를 UTC로 해석)이었음을 그 로그 부재로 확정 —
  콘솔 경로(toApiTime의 UTC 변환)는 처음부터 옳았다.
- **알람 스킵**: 웹훅 메시지에는 버튼 컴포넌트가 안 붙는다(봇 소유 메시지 전용) — 레퍼런스의 버튼을
  음소거 이모지 반응으로 대응. AlertMuter(인스턴스별 일시 중지 1시간, 만료 자동 재개)를 신설하고
  강제 지점은 WebhookNotifier 한 곳(LBAC 단일 경계와 같은 논리). 봇이 중지 확인 답글. 단위 2건
  (중지 인스턴스만 생략·만료 재개).
- **Slack Events 인바운드**: 레퍼런스의 원 채널 대응 — v0 HMAC 서명 검증(상수 시간 비교·5분 리플레이
  창), url_verification challenge, reaction_added(:mag:) → 알림 본문에서 인스턴스 해소 → 진단 →
  스레드 답글. signing secret 미설정 404 게이트, 채널·유저 화이트리스트 기본 거부. 검증 범위(정직):
  실 워크스페이스 미보유라 서명 시뮬레이션 단위 3건까지 — Discord 88절과 같은 모델.
- **Mongo 장기 조회 시간대별 샘플링**: millis 상위만 뽑으면 폭주 시간대가 목록을 독점한다 — 상위
  3N을 시(hour) 버킷 라운드로빈으로 N개 샘플링(버킷 내 millis 순 유지). 단위 2건(독점 방지·단일 버킷).
- **라이브 화면 실측(Playwright)**: 로그인 후 세 장면 촬영 — 검색·필터 바와 역할 배지(webui/53),
  "조회하는/비교하는 시간 범위(KST)" 줄(webui/54), 명령별 차트 그리드(SELECT mean 0.39·max 0.58,
  Mean/Max/Min 범례, webui/55). 촬영 중 진짜 회귀를 잡았다 — 콘솔 MCP 카드가 페이지 로드 때 /mcp를
  호출하는데 OAuth 전용 체인(91절) 이후 401이 되고, SPA의 api() 래퍼가 그 401을 보고 로그인으로
  리다이렉트해 **로그인한 사용자가 콘솔 진입마다 튕겨나갔다**. MCP 카드만 래퍼를 우회(raw fetch)하고
  401을 "토큰 인증 전용" 안내로 표기해 해소 — 화면을 실제로 열어봐야만 잡히는 종류의 버그다.
- **발표 문서 재구성**: PRESENTATION.md를 레퍼런스 덱 구조(AS-IS→인용→TO-BE→목표→3단 기능 지도→
  단계별 화면 16장→MCP 루프·보안 3단계→Lessons Learned→확장 축→마치며)로 전면 재구성 — 수치는
  전부 기존 VERIFICATION 절 인용, 산문 em-dash 연결 없음.

## 99. 문의 첨부 "관련 테이블 구조"의 파티션 부모 오판 수정 — 행수 0·인덱스 없음 회귀

V18에서 query_snapshot을 파티션 테이블로 바꾼 뒤, 문의 카드의 관련 테이블 구조가
"≈ 0행 · idx: (없음)"으로 나가는 회귀를 실물 embed 대조로 발견했다. 원인은 두 곳:

- **행수 0**: 파티션 부모(relkind='p')는 pg_stat_user_tables에서 n_live_tup=0,
  pg_class.reltuples=-1 — 실데이터는 리프에 있다. 실측: 부모 0행 vs 리프
  query_snapshot_y2026m07 472,217행.
- **인덱스 없음**: describeSchema 인덱스 쿼리가 relkind='r'만 잡아 부모의 파티션드
  인덱스(2개)가 통째로 탈락.

수정 세 갈래:

- PostgresOperator.tableDetail: 파티션 부모면 pg_partition_tree로 리프 합산
  (행수는 미ANALYZE 리프 제외, 전부 미ANALYZE면 -1 정직). note에 합산 사실 명시.
- PostgresOperator.describeSchema: relkind IN ('r','p')로 부모 인덱스 포함.
- ReferencedSchemaService: 존재 확인된 테이블을 tableDetail로 승격 — 문의 첨부가
  테이블 상세 화면과 같은 원천(행수·데이터/인덱스 크기·인덱스 타입·카디널리티)을 쓴다.
  tableDetail 실패 시 기존 요약 폴백(첨부가 문의를 막지 않는 원칙 유지). 콘솔
  미리보기(app.js)도 같은 재료 표기.

검증: 478건 그린. 라이브로 /api/instances/4/referenced-schema(도그푸딩 쿼리) 응답이
rowCountApprox=472,217, dataBytes=156,950,528, indexBytes=25,157,632,
인덱스 idx_snapshot_instance_time(instance_id,captured_at) btree +
query_snapshot_pkey[U](id,captured_at) btree — 수정 전 "0행·없음"과 대비.
전 필드 문의 실발사 {"sent":true} (검증용 임시 admin은 사용 직후 삭제).
카디널리티는 null — 파티션 부모에 pg_stats 통계가 없어 미확보 정직 표기(위장 금지 원칙).

## 100. 장기 베이스라인 수신·병합(D8) + plan 보존 시간 하한(D2) — lakehouse 루프의 반대편

Phase 5 reverse ETL의 DBTower 몫. lakehouse가 되쓰는 `baseline_longterm`을 Flyway로
정의(V24)하고, BaselineService가 (요일×시간대) 장기 통계를 단기 14일 창에 **가중
병합**한다 — 충분통계량 복원(Σx=n·m, Σx²=(n−1)s²+n·m²)이라 원시 관측을 더한 것과
수학적으로 동일. 단위 정합: 장기는 시간당 delta_calls → QPS로 /3600. 장기가 나르는
축은 호출량(QPS)뿐 — 레이턴시·행수는 단기 관측이 충분할 때만 판정(얕은 표본 오탐 방지).

### 100-1. V24 라이브 적용 + 실제 되쓰기 왕복

```
Migrating schema "public" to version "24 - baseline longterm"
Successfully applied 1 migration ... now at version v24
lakehouse writeback → {'enabled': True, 'rows': 32498}   (Flyway 정본 테이블에 적재)
```

운영 발견: 검증용 수동 테이블을 DROP하고 Flyway가 재생성하자 `lakehouse_writer`
GRANT가 함께 사라져 첫 되쓰기가 permission denied — **DDL 재생성 시 GRANT 재적용**이
운영 절차에 필요하다(역할·GRANT는 환경 소유 — V24 주석·operations.md).

### 100-2. 병합 라이브 실측 — 실측 스파이크의 판정이 장기 테이블에 따라 뒤집힌다

실제 부하(psql로 `SELECT 1 AS d8_spike` 3,000회/5분, 대상 sample DB)를 만들어 신규
쿼리를 발생시키고, 그 쿼리의 장기 버킷(dow=6, hour=1)을 조작하며 같은 스캔을 반복했다:

```
장기 없음(신규 쿼리)      → 학습중(판정 보류)                        ← 기존 동작
장기 주입: 평소=0.2qps    → 이상 발화: qps 현재=7.47 장기평균=0.3
                            z=7.42, 관측=101                          ← 병합 실물
```

**관측=101 = 장기 100 + 단기 1** — 가중 병합이 게이트(minObservations=8)를 넘겨 판정을
가능하게 했고, z는 병합 통계로 계산됐다. 반대 방향(평소가 높음을 알아 오탐 억제)은
단위 테스트로 고정: `월요일_피크를_아는_장기_평균이_오탐을_없앤다`(단기 8관측 평소
1.0qps + 현재 5.0 = 이상 → 장기 100관측 "평소 5.0" 병합 후 무경보). 회귀 0도 테스트로
고정: 장기 테이블이 비면 병합 전과 판정·z 동일. 정직 표기: 라이브에서 "판정 가능+정상"
단계는 배치 타이밍이 스파이크 꼬리를 놓쳐(mean=0 주입) 증명력이 없었다 — 그 방향은
위 단위 테스트가 커버한다.

### 100-3. D2 — plan_snapshot 보존 시간 하한(48h) 병행

카운트 단독 보존(쿼리당 최신 20)은 플랜이 자주 뒤집히는 쿼리에서 "어제" 행을 하루가
닫히기 전 밀어낼 수 있다 — lakehouse의 D+1 하루창 추출 계약과 어긋난다. 스윕에
`retention-min-age-hours`(기본 48) 하한 추가: cutoff보다 어린 행은 세대 초과여도 보존
(테이블이 일시적으로 N을 넘는 것이 의도된 트레이드오프). 통합 테스트 3건 —
순수 카운트(하한 없음 경로) 기존 2건 + 어린 행 보존/오래된 초과분만 삭제 1건. H2
PostgreSQL 모드에서 네이티브 윈도우 쿼리 검증, 전 스위트 그린.

잔여: D8 라이브의 "억제 방향" 실증은 4주 이력이 쌓인 실버킷에서(합성 주입 없이) 재확인
대상. baseline_longterm의 되쓰기 주기·deadman 편입은 lakehouse 쪽 스케줄 결정.

## 101. 대기 이벤트 주기 영속(D1) — "지금"을 이력으로

Phase 5 forward의 마지막 선결. waitEvents()는 B1부터 5기종을 조회했지만 영속이 없어
"지난달 그 장애 때 뭘 기다렸나"에 답할 수 없었다. V25 `wait_event_snapshot` +
`WaitEventSnapshotJob`(5분 주기 수집·시간창 인덱스·7일 보존 스윕, 둘 다 ShedLock) 신설 —
조회 모델(WaitEvent record)의 실형 그대로 `event_name·category·wait_count·total_ms`.

```
Migrating schema "public" to version "25 - wait event snapshot" → Successfully applied
첫 사이클: 대기 이벤트 영속 완료 instances=6 rows=134
  (MSSQL 37 · Oracle 50 · MSSQL(pitr) 37 · Mongo 6 · PG 3 · self-PG 1 — 기종별 실형이 그대로 드러남)
lakehouse 편입: 레지스트리 available=True + 실스키마 정정 → 134행 멱등 추출,
  게이트 정합·드리프트 OK (freshness FAIL은 열린 오늘 dt라 차단이 정답 — 그쪽 14-5절)
```

수집 격리 토글(collectionEnabled)·인스턴스 실패 격리는 SnapshotScheduler와 동일 계승.
미지원/무대기 기종은 빈 목록 → 행을 지어내지 않는다. 잔여: 이 이력 위의 장기 마트
(기종별 누적/스냅샷 의미 차이를 해석하는 dbt 모델)는 lakehouse 후속 아크.

## 102. 오브젝트 크기 주기 영속(size_snapshot) — lakehouse 용량 예측의 원료

단기 디스크 ETA(78절)는 "이 속도면 0.7일 내"라는 라이브 카나리아다. "몇 달 뒤 꽉
차나·다음 분기 예산"은 크기의 **장기 시계열**이 필요하고 그 계산은 lakehouse 몫 —
여기는 원료만 공급한다(V26 + SizeSnapshotJob: 6시간 주기 tableStats 영속, 7일 보존,
ShedLock·수집 격리·인스턴스 실패 격리는 wait_event 잡과 동일 계승).

```
Migrating schema "public" to version "26 - size snapshot" → Successfully applied
첫 사이클: 크기 스냅샷 영속 완료 instances=6 rows=43
  (inst 4 self-PG 185MB · inst 2 PG 70MB · inst 7 Mongo 5.5MB · …)
lakehouse: 편입·43행 멱등·게이트 통과 → fct_size_daily·mart_capacity_forecast
  (산식은 그쪽 unit test로 고정 — 그쪽 VERIFICATION 15절)
```

volume_total/available·max_bytes 컬럼은 계약상 nullable로 두고 **채우지 않는다** —
기종별 볼륨 조회(MSSQL dm_os_volume_stats·Oracle maxbytes)는 후속 아크. 지어내지 않는다.

## 103. 자연어 서빙 — MCP 장기 마트 도구 2종 (lakehouse 15단계의 DBTower 몫)

Metabot은 Cloud 전용이라 셀프호스트에 없다(lakehouse ROADMAP 15단계 웹 검증). 그 갭을
기존 부품 재조립로 메웠다: 에이전트 → MCP(도구 14→16종) → REST(/api/lakehouse/*) →
**Metabase API**(DuckLake 서빙 계층 재사용 — DuckDB JDBC 직접 의존 0) → 장기 마트.

- `lakehouse_query` — 장기 마트 SELECT(도구 설명에 실재 테이블·컬럼 명시: 지어내기 방지).
  SELECT/WITH 전용 가드(주석 제거 후 판정·세미콜론/쓰기·DDL 거부, 단위 4건) + 행 상한.
  최종 방어는 Metabase 커넥션 자체가 DuckLake를 read-only로 무는 구조.
- `lakehouse_card_create` — 질의를 Metabase 카드로 저장하고 URL 반환. 전용 컬렉션
  "DBTower AI"에 격리 생성(사람 대시보드 오염 방지).
- MetabaseClient: API 키(x-api-key) 또는 관리자 세션(401 시 1회 재로그인), duckdb 엔진
  DB 자동 탐색·캐시. 미설정이면 컨트롤러가 404(기능 게이트 — 있는 척 안 함).

```
질의: mart_capacity_forecast → {"columns":[...],"rows":[[4,1,true,"learning",194.3],...],"row_count":6}
가드: DELETE FROM ... → 400 {"error":"SELECT/WITH로 시작하는 읽기 질의만 허용한다"}
카드: {"card_id":76,"url":"http://localhost:13001/question/76","collection":"DBTower AI"}
  → 실화면: bar 차트 6행·143ms 렌더(스크린샷 — 블로그 19편)
함정: Boot 4 기본 Jackson 3가 Jackson 2 JsonNode를 POJO로 직렬화(메타데이터 덤프) —
  컨트롤러가 JSON 문자열로 직접 응답해 해소
```

정직한 한계: Metabase 화면 안 채팅 UI는 아니다(대화는 에이전트에서, 결과물이 Metabase에
남는 형태). 관제 대상 DB 직결 질의 도구는 이번에도 만들지 않았다(3계층 분업 유지).

## 100. 문의 첨부를 테이블 상세형으로 — 기종별 DDL + 기본 통계 + 카디널리티

99절에서 값을 고쳤다면 이번엔 형태다. 실물 대조에서 "관련 테이블 구조가 콘솔 테이블 상세
(CREATE TABLE·통계·인덱스 카디널리티)와 달리 한 줄 요약"이라는 지적을 받아, 문의 embed를
테이블 상세와 같은 원천·같은 구성으로 재구성했다.

- RefTable에 ddl·ddlSource를 실어(tableDetail 원천) 테이블당 embed 필드 하나:
  DDL 코드블록(760자 절단 — 통계·카디널리티 줄 몫을 남김) + "≈ 행수 · 데이터 · 인덱스" 줄
  + 카디널리티 줄(확보된 인덱스만, 4개까지).
- 기종별 DDL 원천: MySQL=SHOW CREATE TABLE 원문, Oracle=DBMS_METADATA 원문,
  PG/MSSQL=카탈로그 재구성("DDL 재구성" 라벨 명시 — 원문 위장 금지), Mongo=컬렉션 JSON(json 코드블록).
- 참조 테이블 3개 이상이면 앞 2개만 DDL, 나머지는 기존 인덱스 중심 요약 필드(embed 필드 25개 한도 방어).
  텍스트 폴백(Slack·웹훅 미설정)은 기존 컴팩트 요약 유지.
- 경보 카드에는 의도적으로 미탑재 — 자동 발사는 가볍게, DDL은 문의·진단 몫.

검증: 482건 그린(동시 진행된 V24~V26 작업 합류 후). 라이브로 referenced-schema 응답에
ddl="CREATE TABLE query_snapshot (...)" 전문·ddlSource=RECONSTRUCTED·행수 494,797 확인,
전 필드 문의 실발사 {"sent":true} (임시 admin 사용 직후 삭제). formatCompact/formatStats는
jshell 정적 검증 병행(통계 줄·카디널리티 표기 실출력 확인).

### 102-1. volume 공급 개시 (임계 원천 ② — 마무리 아크)

`DbmsOperator.volumeStat()` 신설(default empty — 미지원 기종은 조회 자체가 없다):
MSSQL은 dm_os_volume_stats(볼륨 총량/여유), Oracle은 dba_data_files(할당 합·autoextend
상한 합, 볼륨 여유는 모름=null). SizeSnapshotJob이 인스턴스별 1회 조회해 행에 스탬프.

```
Oracle 라이브: volume_total=1189MB · max_bytes=96TB · available NULL(정직)
MySQL/PG/Mongo: NULL 유지(SQL로 볼륨 불가 — 지어내지 않음)
MSSQL 라이브(컨테이너 복구 후 재사이클): volume_total=1007GB · available=774GB ·
  max_bytes NULL(볼륨이 한도) — 세 기종 판정 전부 실측 완료
lakehouse 소비: 마트가 seed 부재 시 'volume_reported' 폴백(그쪽 16절 unit test 고정)
```
