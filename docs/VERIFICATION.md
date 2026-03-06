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
