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

## 34. Phase B8 — DB팀 문의 채널: KDMS 3단계 완성

한계: KDMS 흐름은 1.식별 2.분석 3.DB팀 문의인데, 3단계(분석 결과를 첨부해 DBA에게 문의)가
비어 있었다. 회귀 감지 웹훅이 "플랫폼이 미는 push"라면, 문의는 "사람이 분석을 첨부해 보내는 push".

- InquiryService/Controller를 alert 모듈에 배치 — insight에 두면 WebhookNotifier(alert) 참조로
  insight<->alert 순환이 되므로(RegressionDetector가 이미 alert->insight). ModularityTests로 확인
- 쿼리·실행계획·규칙 지적·AI 분석·비고를 플레인 텍스트로 포맷해 WebhookNotifier로 전송
- 웹훅 미설정이면 sent:false + 이유(외부 전송 안 함), 인증 사용자면 VIEWER도 문의 가능(협업)
- 웹 콘솔 쿼리 상세 패널에 "DB팀에 문의" 버튼 — explain/AI 결과를 캐시해 함께 첨부

실측: 웹훅 미설정 경로 sent:false 확인(외부 전송 0건), 전송 경로는 WebhookNotifier mock 단위
테스트로 증명. POST라 A6 자동 감사. 이로써 KDMS 1·2·3단계가 전부 채워졌다.

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
| B8 | DB팀 문의 | 분석을 첨부해 어떻게 넘기나(KDMS 3단계) | 34 |

남은 Phase B: B4(gh-ost 온라인 스키마 변경), B5(장기 트랜잭션·복제 지연·용량 알림), B6(파라미터 드리프트).
5기종 통합은 전부 "DbmsOperator 메서드 1개 추가"로 이뤄졌다 — 아키텍처 주장의 반복 검증.
