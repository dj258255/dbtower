# 실행계획 판단 규칙 (기종별)

RuleBasedAnalyzer가 사용하는 비효율 판단 기준과 그 근거를 정리한다.
확장3에서 LLM 1차 분석을 붙일 때 이 문서가 그대로 프롬프트의 판단 기준이 된다.
(같은 입력에 같은 판정 — AI에게 판단을 통째로 맡기지 않고 기준을 명시하는 이유)

이 규칙의 원리는 대부분 db-hobby(C로 만든 미니 RDBMS)에서 저장 계층·B+Tree·실행기·플래너를
직접 구현하며 확인한 것이다. 각 규칙에 그 연결을 남긴다.

## 핵심 전제 — 왜 "풀스캔"이 신호인가

인덱스가 없으면 조건에 맞는 행을 찾는 비용이 O(n)이다. 테이블 전체를 처음부터 끝까지 읽어야 하기 때문.
B+Tree 인덱스가 있으면 이걸 O(log n)으로 줄인다 — 루트에서 리프까지 몇 단계만 타면 되니까.
db-hobby에서 인덱스 없이 힙을 순차 스캔하다가 B+Tree를 붙여 조회를 O(n)에서 O(log n)으로 바꾼 게
이 판정의 출발점이다. 그래서 실행계획에 "테이블 전체를 읽는다"는 신호가 보이면 인덱스를 의심한다.

단, **풀스캔이 항상 나쁜 것은 아니다**(아래 각 규칙의 예외 참고). 그래서 규칙은 신호를 "지적"할 뿐,
행수·선택도와 함께 사람(또는 AI 1차 분석)이 최종 판단하도록 설계했다.

## MySQL

| 신호 | 판정 | 근거 | 예외 (오탐 가능) |
|---|---|---|---|
| access_type=ALL | 테이블 풀스캔 | 인덱스 부재, 또는 앞 와일드카드 LIKE 등으로 인덱스 사용 불가 | 테이블이 작으면(수백 행) 풀스캔이 인덱스 조회보다 빠르다 — 랜덤 I/O보다 순차 I/O가 싸기 때문 |
| using_filesort=true | 별도 정렬 | ORDER BY가 인덱스 순서로 해결되지 않아 결과를 다시 정렬 | 정렬 대상이 소량이면 무시 가능. 인메모리 정렬이면 디스크 스필보다 훨씬 쌈 |
| using_temporary_table=true | 임시 테이블 | GROUP BY/DISTINCT가 인덱스로 해결되지 않음 | 소량 집계면 정상. GROUP BY 컬럼에 인덱스가 있으면 사라진다 |
| access_type=index | 인덱스 풀스캔 | 인덱스 전체를 훑음 — 리프를 처음부터 끝까지 읽는 것 | 커버링 인덱스라 테이블 접근이 없으면 풀스캔보다 나을 수 있다 |

**앞 와일드카드 LIKE가 왜 인덱스를 못 타는가** (db-hobby 6편에서 직접 확인):
B+Tree는 "정렬된 순서"로 값을 찾는다. `LIKE 'abc%'`는 접두사가 고정이라 트리에서 시작점을 잡을 수 있지만,
`LIKE '%abc'`는 뒤만 고정이라 시작점을 잡을 수 없어 결국 전체를 훑는다. db-hobby에서 LIKE를 구현할 때
접두사 패턴만 인덱스 범위 스캔으로 최적화하고 중간/후위 와일드카드는 순차 스캔으로 떨어지는 걸 보고,
"실무에서 LIKE '%keyword%'가 왜 느린가"를 코드로 이해했다. WikiEngine에서 이 풀스캔(2,744만 행 추정)을
검색 엔진 전환으로 푼 것도 같은 원리다.

## PostgreSQL

| 신호 | 판정 | 근거 | 예외 (오탐 가능) |
|---|---|---|---|
| Seq Scan | 순차 스캔 | 테이블 전체를 읽음 | **작은 테이블이면 정상이고 오히려 빠르다** — 플래너가 비용 계산 끝에 일부러 고르기도 한다. 행수와 함께 판단해야 오탐이 안 난다 |
| Nested Loop + 안쪽 Seq Scan | 조인 폭발 | 바깥 행수 x 안쪽 풀스캔 = 곱셈으로 커짐 | 양쪽 다 소량이면 정상. 조인 키에 인덱스가 있으면 Index Scan으로 바뀐다 |
| Sort Method: external | 디스크 스필 정렬 | work_mem을 넘겨 디스크로 정렬 | work_mem을 올리면 인메모리로 해결되기도 한다 |

**Nested Loop가 왜 위험한가** (db-hobby 5편에서 직접 구현):
중첩 루프 조인은 말 그대로 이중 for문이다 — 바깥 테이블 각 행마다 안쪽 테이블을 훑는다.
안쪽이 인덱스를 타면 O(바깥 x log 안쪽)이지만, 안쪽이 Seq Scan이면 O(바깥 x 안쪽)로 곱셈이 된다.
db-hobby에서 조인 알고리즘을 인덱스/해시/중첩루프 중에 고르는 플래너를 만들면서, 안쪽에 인덱스가
없을 때 중첩 루프가 얼마나 급격히 느려지는지 실측했다. 그래서 "Nested Loop 안쪽 Seq Scan"을
단독 Seq Scan보다 더 강한 신호로 본다.

## SQL Server

| 신호 | 판정 | 근거 | 예외 (오탐 가능) |
|---|---|---|---|
| Table Scan | 힙 풀스캔 | 클러스터드 인덱스가 없는 힙 테이블을 전체 읽음 | 작은 룩업 테이블이면 정상 |
| Clustered Index Scan | 사실상 풀스캔 | 클러스터드 인덱스 = 테이블 자체라, 스캔이면 테이블 전체를 읽는 것과 같다 | 결과가 테이블의 대부분이면(저선택도) 스캔이 Seek보다 낫다 |
| Sort | 정렬 연산자 | 정렬 비용 발생 | 인덱스 정렬 순서를 활용하면 사라진다. 소량이면 무시 |

**Clustered Index Scan이 왜 "사실상 풀스캔"인가**:
클러스터드 인덱스는 인덱스가 곧 데이터다 — 리프 노드에 행 전체가 들어 있다(힙 + 별도 인덱스 구조가 아니라
하나로 합쳐진 형태). db-hobby 1편에서 "힙 vs 클러스터드"를 저장 계층 관점에서 비교했는데,
클러스터드는 PK 순서로 데이터가 물리 정렬돼 있어 범위 조회에 강한 대신, 스캔이 걸리면 데이터 전체를
훑는 것과 같아진다. 그래서 "Clustered Index Scan"을 Index Seek과 달리 풀스캔급 신호로 판정한다.

## Oracle

| 신호 | 판정 | 근거 | 예외 (오탐 가능) |
|---|---|---|---|
| TABLE ACCESS FULL | 테이블 풀스캔 | 조건을 받는 인덱스가 없거나, 함수 적용·암시적 형변환·앞 와일드카드로 인덱스를 못 탐 | 작은 테이블, 또는 결과가 테이블 대부분이면 옵티마이저가 일부러 고른다 — 멀티블록 I/O로 풀스캔이 더 싸기 때문 |
| INDEX FULL SCAN | 인덱스 풀스캔 | 인덱스 리프 전체를 순서대로 읽음 — 범위 조건이 시작점을 못 잡는 상태 | ORDER BY를 인덱스 순서로 흡수하려고 의도적으로 선택되기도 한다 |
| SORT ORDER BY | 별도 정렬 | ORDER BY가 인덱스 순서로 해결되지 않아 결과를 다시 정렬 | 소량 정렬이면 무시 가능. PGA 안에서 끝나면(Optimal) 디스크 스필보다 훨씬 쌈 |

판정 원리는 MySQL과 동일하다 — B+Tree가 시작점을 잡을 수 있느냐의 문제.
Oracle은 실행계획을 텍스트 표(DBMS_XPLAN)로 주기 때문에 연산자 이름 문자열이 곧 신호가 된다.
(출처: Oracle Database SQL Tuning Guide — Full Table Scans 절)

## MongoDB

| 신호 | 판정 | 근거 | 예외 (오탐 가능) |
|---|---|---|---|
| COLLSCAN | 컬렉션 풀스캔 | 필터를 받는 인덱스가 없어 컬렉션 전체를 훑음 — SQL의 테이블 풀스캔과 동일한 신호 | 컬렉션이 작으면 정상. hint로 강제하지 않는 한 인덱스가 생기면 IXSCAN으로 바뀐다 |
| SORT 스테이지 | 인메모리 정렬 (blocking sort) | 인덱스가 정렬 순서를 제공하지 못해 결과를 메모리에서 정렬 | 소량이면 무시 가능. 단 정렬 메모리 한도(100MB)를 넘으면 allowDiskUse 없이는 쿼리가 실패한다 — RDB보다 결과가 극단적 |

MongoDB는 실행계획이 JSON(winningPlan의 stage 트리)이라 stage 이름이 곧 신호다.
같은 인터페이스(explain)에 입력만 SQL 대신 명령 JSON이 들어간다 —
"기종별 차이를 판단 규칙 문서가 흡수한다"는 이 문서의 역할이 비 SQL 기종에서도 유지되는 것.
(출처: MongoDB Manual — Explain Results, cursor.sort의 메모리 제한 절)

## 판정을 넘어 — 왜 "선택도"가 최종 판단자인가

풀스캔이냐 인덱스냐의 진짜 기준은 **선택도(selectivity)** 다. 조건에 맞는 행이 전체의 몇 %인가.
- 1%만 뽑는다면 인덱스로 그 1%만 찾는 게 압도적으로 싸다.
- 80%를 뽑는다면 인덱스로 80%를 랜덤 접근하는 것보다 그냥 전체를 순차로 읽는 게 싸다
  (랜덤 I/O > 순차 I/O). 그래서 플래너가 일부러 풀스캔을 고른다.

db-hobby 8편에서 EXPLAIN을 직접 구현할 때, 어떤 접근 경로를 고를지가 결국 "얼마나 걸러지는가"의
추정에 달려 있다는 걸 확인했다. 그래서 이 규칙들은 신호를 지적하되 자동으로 "나쁘다"고 단정하지 않고,
행수·rows examined와 함께 제시한다. 회귀 감지(확장3)가 "rows/call 폭증"을 별도 신호로 두는 것도
같은 맥락 — 실행계획 텍스트만으로는 선택도 변화를 못 보니, 실제로 읽은 행수의 변화를 함께 본다.

## 심층 원인 규칙 (D9) — "무엇이 느린가"를 넘어 "왜 인덱스를 못 타나"

위 기종별 규칙은 **증상**(풀스캔·정렬)을 잡는다. 여기서부터는 **근본 원인**이다.
핵심 열쇠는 하나 — **옵티마이저의 추정 행수 vs 실제 행수의 괴리(카디널리티 오추정)**.
추정이 크게 틀리면 옵티마이저는 "그 추정 기준으로는 합리적인" 나쁜 플랜을 고른다.
느린 노드가 아니라, **추정과 실제가 크게(10배+) 갈라지는 가장 아래(최하위) 노드**가 원인이다.

### 추정 vs 실제를 보는 법 (기종별 — 실제 실행 계획 필요)

| 기종 | 방법 | 추정 vs 실제 신호 | 주의 |
|---|---|---|---|
| MySQL 8.4 | `EXPLAIN ANALYZE`(TREE 포맷) — **8.4는 EXPLAIN ANALYZE에 FORMAT=JSON을 아직 거부(ERROR 1235)하므로 TREE 실측**. `(actual time=.. rows=<actual_rows> loops=<loops>)` 파싱 | estimated_rows vs actual_rows, filtered | actual_rows는 **loops당 평균** — 총량은 loops를 곱해야 한다 |
| PostgreSQL | `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` | Plan Rows vs Actual Rows, Rows Removed by Filter, Shared Hit/Read Blocks | Actual Rows·시간은 **loops당 평균**(공식 문서 명시) — Nested Loop 안쪽에서 특히 오독 주의 |
| Oracle | 쿼리에 `/*+ gather_plan_statistics */` 힌트 후 같은 세션에서 `DBMS_XPLAN.DISPLAY_CURSOR(format=>'ALLSTATS LAST')` | E-Rows vs A-Rows | 같은 커넥션 필수(기존 explain의 ConnectionCallback 패턴). 필요한 V$ 권한은 SELECT_CATALOG_ROLE에 포함(모니터링 계정 이미 보유) |
| SQL Server | `SET STATISTICS XML ON` 후 실행 — 플랜이 **별도 결과셋**(XML)으로 옴 | EstimateRows vs RunTimeInformation/ActualRows, 플랜 안의 missing index·경고(spill·implicit conversion) | JDBC에선 PreparedStatement가 아니라 plain Statement + getMoreResults()로 결과셋 순회 |
| MongoDB | `explain`을 verbosity `executionStats`로 | totalDocsExamined ÷ nReturned 비율(스캔 낭비), totalKeysExamined vs totalDocsExamined, rejectedPlans | executionStats는 실제 실행 — maxTimeMS 필수 |

**실행 안전(전 기종 공통)**: 실제 실행 계획은 쿼리를 진짜 실행한다. SELECT 전용(기존 requireSelect) +
타임아웃 필수 — MySQL은 `/*+ MAX_EXECUTION_TIME(ms) */`(SELECT 전용 힌트), PG는 트랜잭션 내
`SET LOCAL statement_timeout`, Mongo는 maxTimeMS, Oracle/MSSQL은 JDBC setQueryTimeout.
진단 도구가 부하 유발자가 되면 안 된다.

### 인덱스 무력화 근본원인 5종 (증상이 아니라 원인)

| 원인 | 왜 인덱스를 못 타나 | 감지 신호 | 특히 심한 기종 |
|---|---|---|---|
| 암시적 형변환 | 문자열 컬럼 = 숫자 비교 시 컬럼 전체를 변환해야 해서 seek이 scan이 된다 ('1', ' 1', '1a'가 모두 1로 변환되므로 인덱스 순서를 못 쓴다) | 컬럼 타입 vs 비교값 타입 불일치. MSSQL은 플랜에 CONVERT_IMPLICIT 경고 | MySQL·MSSQL (VARCHAR 컬럼에 INT 파라미터가 단골) |
| 컬럼에 함수/표현식 | LEFT(col), YEAR(dt) 등 — 옵티마이저가 표현식 결과를 미리 알 수 없어 인덱스 무력화 | WHERE 절에서 컬럼이 함수 안에 있는 패턴 | 전 기종 (해법: 함수를 상수 쪽으로, 또는 함수 기반 인덱스) |
| 통계 노후 | 옵티마이저가 옛날 행수·분포로 추정 → 잘못된 플랜 | 추정 vs 실제 괴리 + 통계 수집 시각 오래됨(D2 stale-statistics Advisor와 짝) | 전 기종 |
| 낮은 선택도 / 다중 컬럼 상관 | 결과가 테이블 대부분이면 옵티마이저가 일부러 풀스캔(정상). 다중 컬럼 조합 추정은 단일 컬럼 통계로는 자주 틀림(PG는 CREATE STATISTICS로 보정) | 추정 vs 실제 괴리가 조인·GROUP BY 노드에서 발생 | PG(n_distinct 조합), 전 기종 |
| 복합 인덱스 선두 누락 / 앞 와일드카드 | B+Tree는 선두 컬럼부터 시작점을 잡는다 — 선두 없이 뒷 컬럼만 조건이면 못 탄다. LIKE '%x'도 동일 원리(위 MySQL 절 참고) | 인덱스 정의(describeSchema) vs WHERE 컬럼 대조 | 전 기종 |

이 표가 D3(자연어 진단)와 AI 1차 분석의 프롬프트 근거가 된다 — AI는 "풀스캔입니다"에서 멈추지 말고,
추정 vs 실제 괴리와 위 5종 원인 중 어디에 해당하는지까지 근거를 들어 짚어야 한다. 근거가 없으면 모른다고 답한다.

## AI 1차 분석 프롬프트에서 이 문서의 역할

확장3의 AiAnalyzer는 이 문서 전체를 system 프롬프트로 넣고 "반드시 이 기준에 근거해서만 판정하고,
근거가 없으면 모른다고 말하고, 수치를 지어내지 말라"고 지시한다. AI는 판단자가 아니라, 사람이 정한
기준 위에서 1차로 훑는 도구다. 규칙에 없는 새 패턴이 자주 나오면 그때 이 문서에 규칙을 추가한다.
