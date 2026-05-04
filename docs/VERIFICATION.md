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

시나리오 — KDMS 발표의 실사례 3종을 재현:

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

| 검출 | KDMS 실사례 |
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
당근 KDMS가 4096으로 상향한 것과 같은 판단 — 우리는 docker-compose에서 처음부터 반영했다.

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
같은 입력에 일관된 판정이 나오게 한다(KDMS와 같은 접근). ANTHROPIC_API_KEY 미설정이면
조용히 비활성화되고 규칙 기반 알림만 발송 — 분석 실패가 알림 자체를 막지 않는다.

## 16. 확장4 — 웹 UI (시점 비교 + EXPLAIN + AI 분석 화면)

REST API 위에 의존성 없는 정적 SPA를 올렸다 (Spring Boot가 함께 서빙 — java -jar 하나로 화면까지).
화면 구도는 당근 KDMS Database Insight를 참고: 그래프에서 구간을 드래그로 고르고,
Top Query 증감과 신규 쿼리를 보고, 클릭 한 번으로 실행계획과 분석까지 내려간다.

E2E 시나리오 (실측 2026-07-03):
1. 베이스라인 부하 3분(점조회) -> 급증 부하 2분(점조회 x3 + 신규 LIKE 풀스캔 COUNT)
2. 비교 구간 15:20:30~15:23:10 vs 조회 구간 15:23:10~15:25:30 으로 비교 조회

결과 (스크린샷 docs/images/webui/):

- 01-dashboard.png — 이기종 4대(MySQL/PG/MSSQL/dbtower-self) 헬스·버전 카드,
  활동 그래프(QPS)에 부하 스파이크가 그대로 보임. 그래프는 스냅샷 누적 카운터의
  인접 배치 차분 — 시점 비교와 같은 원리의 다른 뷰라 추가 수집이 없다.
```
GET /api/instances/1/activity?from=...&to=...
[{"time":"...15:18:00","qps":0.48,"avgLatencyMs":0.94}, ...]
```
- 02-compare.png — 비교 조회. 요약 스트립 "호출량 +174% / 평균 레이턴시 +12% /
  읽은 행수 +217% / 신규 쿼리 1개". 신규 LIKE 쿼리에 NEW 뱃지 + 행 하이라이트,
  기존 점조회는 QPS 2.48 -> 11.8 (▲ 9.32) 증감 표시.
```
GET /api/instances/1/compare?... -> newQueryCount: 1, totalCallsChangePct: 174.08
```
- 03-explain.png — NEW 쿼리 클릭 -> 정규화 텍스트의 파라미터(?)를 실제 값으로 고쳐
  실행계획 실행 -> access_type=ALL(풀스캔), rows_examined_per_scan=7926 확인,
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
같은 코어를 채널만 바꿔 노출한다. 당근 KDMS가 시점비교·실행계획 등을 MCP로 제공해
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
tools/call compare(부하 구간)  -> newQueryCount: 1, totalCallsChangePct: 174.08
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
POST /mcp tools/call compare(부하구간) -> newQueryCount: 1, totalCallsChangePct: 174.08
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

실측 (04-ai.png 교체, 응답 약 16초):
LIKE '%user1%' 풀스캔 쿼리에 대해 — access_type=ALL의 원인을 앞 와일드카드로 특정하고
(B+Tree 시작점 원리 인용), filtered=11.11을 문서 기준으로 해석하고, 접두사 전환/FULLTEXT/
검색 엔진 전환까지 판단 기준 문서 안에서만 제안했다. 문서 밖 수치에 대해서는
"주어진 계획만으로 판단할 수 없다"고 답해 — "근거 없으면 모른다고 말하라"는 프롬프트 규칙이
실제로 지켜지는 것을 확인했다.
