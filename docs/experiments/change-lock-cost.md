# E1: 변경 실행 안전장치의 락 비용 측정

이 문서와 `change-lock-cost.csv`는 `ChangeLockCostExperimentIT`가 실제 대상 DB에 대고 측정해 생성한 결과다 — 숫자를 손으로 고치지 않는다. 실행 방법: `DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*ChangeLockCostExperimentIT'` (게이트 환경변수는 Gradle 태스크 입력이 아니라, 이미 한 번 돌린 뒤면 `cleanTest`를 앞에 붙여야 다시 돈다)

## 실행 환경

| 기종 | 버전 | 격리 수준 | 실험 테이블 행 수 |
|---|---|---|---|
| MySQL | 8.4.11 | REPEATABLE-READ | 20000 |
| PostgreSQL | PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit | read committed | 20000 |

- 실행 일시: 2026-09-17T01:33:52.245327+09:00
- 반복: 조합마다 워밍업 2회를 버리고 7회 측정. 표의 값은 그 7회의 중앙값과 p95다(7표본에서 최근접 순위 정의상 p95는 최댓값과 같다)
- 대상 행 수 k: [10, 100, 1000, 10000], 사본 상한 maxRows=10000(제품 기본값)
- 변경 문장: `UPDATE exp_change_lock SET note='exp' WHERE id <= k`, 사본 조회: `SELECT * FROM exp_change_lock WHERE amount <= k FOR UPDATE` (amount == id)
- 락 보유 시간 = 커밋 완료 시각 − 잠그는 첫 문장 실행 시작 시각(둘 다 같은 커넥션에서 프록시로 기록). CAPTURED의 잠그는 첫 문장은 사본 조회(FOR UPDATE), UNCAPTURED는 변경 문장 자체다
- 실험 테이블: exp_change_lock(id BIGINT PK, grp INT 0~99 균등, amount INT = id, note VARCHAR(64)), 20000행

## 표 1: 락 보유 시간(ms)

| 기종 | 조건 인덱스 | k | CAPTURED 중앙값 | CAPTURED p95 | UNCAPTURED 중앙값 | UNCAPTURED p95 | 차이(ms) | 배수 |
|---|---|---|---|---|---|---|---|---|
| MySQL | indexed | 10 | 5.31 | 7.25 | 2.77 | 2.99 | +2.54 | 1.9배 |
| MySQL | indexed | 100 | 6.32 | 21.44 | 3.39 | 4.35 | +2.93 | 1.9배 |
| MySQL | indexed | 1000 | 21.00 | 22.59 | 6.46 | 16.93 | +14.54 | 3.2배 |
| MySQL | indexed | 10000 | 156.19 | 212.25 | 38.33 | 71.49 | +117.86 | 4.1배 |
| MySQL | unindexed | 10 | 9.62 | 10.22 | 3.14 | 3.31 | +6.49 | 3.1배 |
| MySQL | unindexed | 100 | 12.41 | 14.62 | 3.64 | 3.86 | +8.77 | 3.4배 |
| MySQL | unindexed | 1000 | 26.26 | 29.64 | 6.96 | 7.11 | +19.30 | 3.8배 |
| MySQL | unindexed | 10000 | 158.75 | 209.74 | 35.19 | 37.78 | +123.56 | 4.5배 |
| PostgreSQL | indexed | 10 | 5.42 | 5.75 | 1.71 | 1.80 | +3.70 | 3.2배 |
| PostgreSQL | indexed | 100 | 6.04 | 6.60 | 1.98 | 2.40 | +4.06 | 3.0배 |
| PostgreSQL | indexed | 1000 | 20.56 | 21.55 | 3.98 | 4.61 | +16.58 | 5.2배 |
| PostgreSQL | indexed | 10000 | 147.06 | 151.72 | 36.52 | 44.18 | +110.54 | 4.0배 |
| PostgreSQL | unindexed | 10 | 6.33 | 8.27 | 1.97 | 2.12 | +4.36 | 3.2배 |
| PostgreSQL | unindexed | 100 | 7.98 | 8.33 | 2.23 | 2.51 | +5.75 | 3.6배 |
| PostgreSQL | unindexed | 1000 | 19.88 | 21.62 | 3.21 | 3.56 | +16.67 | 6.2배 |
| PostgreSQL | unindexed | 10000 | 140.93 | 146.95 | 26.16 | 42.72 | +114.77 | 5.4배 |

- 성공 표본이 0회면 `-`. 조합마다 7회 중 성공한 회차만 통계에 넣는다
- 락 보유 시간은 안전장치가 하는 일 전부를 포함한다: 사본 조회, 변경 문장, 변경 뒤 키 재조회, 커밋. UNCAPTURED는 변경 문장과 커밋뿐이다

## 표 2: 동시 작성자(k=1000, 락 대기 상한 10초, 탐침마다 3회)

| 기종 | 조건 인덱스 | 방식 | 탐침 | 결과 | 대기(ms) | 기준선(ms) | 잔여 락 보유(ms) | 판정 |
|---|---|---|---|---|---|---|---|---|
| MySQL | indexed | UPDATE | update_in_range | SUCCESS, SUCCESS, SUCCESS | 18.39 | 2.13 | 17.51 | 막힘(커밋까지) |
| MySQL | indexed | UPDATE | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 2.32 | 2.12 | 17.95 | 안 막힘 |
| MySQL | indexed | UPDATE | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 22.10 | 2.07 | 20.71 | 막힘(커밋까지) |
| MySQL | indexed | UNCAPTURED | update_in_range | SUCCESS, SUCCESS, SUCCESS | 3.64 | 2.13 | 2.06 | 안 막힘 |
| MySQL | indexed | UNCAPTURED | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 2.35 | 2.12 | 1.81 | 안 막힘 |
| MySQL | indexed | UNCAPTURED | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 3.56 | 2.07 | 2.89 | 안 막힘 |
| MySQL | unindexed | UPDATE | update_in_range | SUCCESS, SUCCESS, SUCCESS | 17.30 | 2.16 | 16.33 | 막힘(커밋까지) |
| MySQL | unindexed | UPDATE | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 18.54 | 2.11 | 17.42 | 막힘(커밋까지) |
| MySQL | unindexed | UPDATE | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 17.11 | 2.04 | 16.17 | 막힘(커밋까지) |
| MySQL | unindexed | UNCAPTURED | update_in_range | SUCCESS, SUCCESS, SUCCESS | 3.07 | 2.16 | 1.92 | 안 막힘 |
| MySQL | unindexed | UNCAPTURED | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 2.63 | 2.11 | 2.12 | 안 막힘 |
| MySQL | unindexed | UNCAPTURED | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 2.66 | 2.04 | 2.07 | 안 막힘 |
| PostgreSQL | indexed | UPDATE | update_in_range | SUCCESS, SUCCESS, SUCCESS | 17.01 | 1.22 | 16.71 | 막힘(커밋까지) |
| PostgreSQL | indexed | UPDATE | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 1.58 | 1.35 | 16.03 | 안 막힘 |
| PostgreSQL | indexed | UPDATE | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 1.31 | 1.08 | 16.92 | 안 막힘 |
| PostgreSQL | indexed | UNCAPTURED | update_in_range | SUCCESS, SUCCESS, SUCCESS | 1.54 | 1.22 | 1.21 | 안 막힘 |
| PostgreSQL | indexed | UNCAPTURED | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 1.48 | 1.35 | 1.13 | 안 막힘 |
| PostgreSQL | indexed | UNCAPTURED | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 1.27 | 1.08 | 1.01 | 안 막힘 |
| PostgreSQL | unindexed | UPDATE | update_in_range | SUCCESS, SUCCESS, SUCCESS | 17.73 | 1.57 | 17.45 | 막힘(커밋까지) |
| PostgreSQL | unindexed | UPDATE | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 1.65 | 1.38 | 16.32 | 안 막힘 |
| PostgreSQL | unindexed | UPDATE | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 1.69 | 1.09 | 18.41 | 안 막힘 |
| PostgreSQL | unindexed | UNCAPTURED | update_in_range | SUCCESS, SUCCESS, SUCCESS | 1.97 | 1.57 | 1.70 | 안 막힘 |
| PostgreSQL | unindexed | UNCAPTURED | update_out_of_range | SUCCESS, SUCCESS, SUCCESS | 1.50 | 1.38 | 1.14 | 안 막힘 |
| PostgreSQL | unindexed | UNCAPTURED | insert_in_range | SUCCESS, SUCCESS, SUCCESS | 1.86 | 1.09 | 1.67 | 안 막힘 |

- 탐침: `update_in_range` = 대상 안 행(id=1000) UPDATE, `update_out_of_range` = 대상 밖 행(id=20000) UPDATE, `insert_in_range` = 대상 조건 범위 안(id=999999, amount=5) 새 행 INSERT. 셋 다 `note`만 바꾼다(조건 열 amount를 건드리지 않아 대상 행 집합이 변하지 않는다)
- `기준선`은 같은 탐침 문장을 변경 없이 단독 실행한 시간(같은 조합에서 3회, 중앙값)이다. 대기가 락 때문인지 문장 자체가 느린 것인지 가르는 대조군이다
- `잔여 락 보유` = 변경의 커밋 완료 − 탐침 출발 시각. 탐침이 락에 막혔다면 대기가 이 값에 수렴한다
- `판정` 규칙: 출발이 변경 커밋 뒤인 회차는 뺀다. 남은 회차의 중앙값이 (기준선 x 2 + 1ms) 이하면 `안 막힘`. 그보다 길고 잔여 락 보유의 절반 이상을 기다렸으면 `막힘(커밋까지)`, 길지만 절반에 못 미치면 `단정 보류(짧은 대기)`. 대기 상한을 넘긴 회차가 있으면 `막힘(타임아웃)`. 두 조건을 모두 요구하는 이유는 UNCAPTURED처럼 잔여 락 보유가 1~2ms인 조합에서 막힘과 문장 자체의 지연을 가를 수 없기 때문이다
- 두 번째 커넥션은 변경 쪽이 잠그는 첫 문장을 끝낸 신호(CountDownLatch) 뒤에 출발한다 — sleep으로 순서를 맞추지 않는다

## 실행계획(참고)

| 기종 | 조건 인덱스 | 단계 | 계획 |
|---|---|---|---|
| MySQL | indexed | capture(k=10) | `type=range key=exp_change_lock_amount_idx rows=10 extra=Using index condition` |
| MySQL | indexed | reselect(10키 OR) | `type=range key=PRIMARY rows=10 extra=Using where` |
| MySQL | indexed | capture(k=100) | `type=range key=exp_change_lock_amount_idx rows=100 extra=Using index condition` |
| MySQL | indexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| MySQL | indexed | capture(k=1000) | `type=range key=exp_change_lock_amount_idx rows=1000 extra=Using index condition` |
| MySQL | indexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| MySQL | indexed | capture(k=10000) | `type=ALL key=null rows=20352 extra=Using where` |
| MySQL | indexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| MySQL | unindexed | capture(k=10) | `type=ALL key=null rows=20352 extra=Using where` |
| MySQL | unindexed | reselect(10키 OR) | `type=range key=PRIMARY rows=10 extra=Using where` |
| MySQL | unindexed | capture(k=100) | `type=ALL key=null rows=20352 extra=Using where` |
| MySQL | unindexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| MySQL | unindexed | capture(k=1000) | `type=ALL key=null rows=20352 extra=Using where` |
| MySQL | unindexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| MySQL | unindexed | capture(k=10000) | `type=ALL key=null rows=20352 extra=Using where` |
| MySQL | unindexed | reselect(100키 OR) | `type=range key=PRIMARY rows=100 extra=Using where` |
| PostgreSQL | indexed | capture(k=10) | `Index Scan using exp_change_lock_amount_idx on exp_change_lock  (cost=0.29..8.46 rows=10 width=21) ; Index Cond: (amount <= 10)` |
| PostgreSQL | indexed | reselect(10키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=42.98..74.94 rows=10 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10)) ; ->  Bitm …` |
| PostgreSQL | indexed | capture(k=100) | `Index Scan using exp_change_lock_amount_idx on exp_change_lock  (cost=0.29..10.04 rows=100 width=21) ; Index Cond: (amount <= 100)` |
| PostgreSQL | indexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=432.00..584.00 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |
| PostgreSQL | indexed | capture(k=1000) | `Index Scan using exp_change_lock_amount_idx on exp_change_lock  (cost=0.29..40.07 rows=1016 width=21) ; Index Cond: (amount <= 1000)` |
| PostgreSQL | indexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=432.00..585.89 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |
| PostgreSQL | indexed | capture(k=10000) | `Index Scan using exp_change_lock_amount_idx on exp_change_lock  (cost=0.29..582.88 rows=19062 width=21) ; Index Cond: (amount <= 10000)` |
| PostgreSQL | indexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=432.25..645.02 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |
| PostgreSQL | unindexed | capture(k=10) | `Gather  (cost=1000.00..5816.53 rows=159 width=21) ; Workers Planned: 1 ; ->  Parallel Seq Scan on exp_change_lock  (cost=0.00..4800.63 rows=94 width=21)` |
| PostgreSQL | unindexed | reselect(10키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=43.08..81.42 rows=10 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10)) ; ->  Bitm …` |
| PostgreSQL | unindexed | capture(k=100) | `Gather  (cost=1000.00..5974.33 rows=1737 width=21) ; Workers Planned: 1 ; ->  Parallel Seq Scan on exp_change_lock  (cost=0.00..4800.63 rows=1022 width=21)` |
| PostgreSQL | unindexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=433.00..789.42 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |
| PostgreSQL | unindexed | capture(k=1000) | `Seq Scan on exp_change_lock  (cost=0.00..6597.28 rows=17453 width=21) ; Filter: (amount <= 1000)` |
| PostgreSQL | unindexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=433.00..789.42 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |
| PostgreSQL | unindexed | capture(k=10000) | `Seq Scan on exp_change_lock  (cost=0.00..6609.10 rows=174844 width=21) ; Filter: (amount <= 10000)` |
| PostgreSQL | unindexed | reselect(100키 OR) | `Bitmap Heap Scan on exp_change_lock  (cost=433.00..789.48 rows=100 width=21) ; Recheck Cond: ((id = 1) OR (id = 2) OR (id = 3) OR (id = 4) OR (id = 5) OR (id = 6) OR (id = 7) OR (id = 8) OR (id = 9) OR (id = 10) OR (id = …` |

## 관찰 (숫자가 보여 주는 것)

### 안전장치가 락을 얼마나 오래 잡는가

- MySQL / indexed / k=10: CAPTURED 중앙값 5.31ms, UNCAPTURED 중앙값 2.77ms → 차이 +2.54ms(1.9배)
- MySQL / indexed / k=100: CAPTURED 중앙값 6.32ms, UNCAPTURED 중앙값 3.39ms → 차이 +2.93ms(1.9배)
- MySQL / indexed / k=1000: CAPTURED 중앙값 21.00ms, UNCAPTURED 중앙값 6.46ms → 차이 +14.54ms(3.2배)
- MySQL / indexed / k=10000: CAPTURED 중앙값 156.19ms, UNCAPTURED 중앙값 38.33ms → 차이 +117.86ms(4.1배)
- MySQL / unindexed / k=10: CAPTURED 중앙값 9.62ms, UNCAPTURED 중앙값 3.14ms → 차이 +6.49ms(3.1배)
- MySQL / unindexed / k=100: CAPTURED 중앙값 12.41ms, UNCAPTURED 중앙값 3.64ms → 차이 +8.77ms(3.4배)
- MySQL / unindexed / k=1000: CAPTURED 중앙값 26.26ms, UNCAPTURED 중앙값 6.96ms → 차이 +19.30ms(3.8배)
- MySQL / unindexed / k=10000: CAPTURED 중앙값 158.75ms, UNCAPTURED 중앙값 35.19ms → 차이 +123.56ms(4.5배)
- PostgreSQL / indexed / k=10: CAPTURED 중앙값 5.42ms, UNCAPTURED 중앙값 1.71ms → 차이 +3.70ms(3.2배)
- PostgreSQL / indexed / k=100: CAPTURED 중앙값 6.04ms, UNCAPTURED 중앙값 1.98ms → 차이 +4.06ms(3.0배)
- PostgreSQL / indexed / k=1000: CAPTURED 중앙값 20.56ms, UNCAPTURED 중앙값 3.98ms → 차이 +16.58ms(5.2배)
- PostgreSQL / indexed / k=10000: CAPTURED 중앙값 147.06ms, UNCAPTURED 중앙값 36.52ms → 차이 +110.54ms(4.0배)
- PostgreSQL / unindexed / k=10: CAPTURED 중앙값 6.33ms, UNCAPTURED 중앙값 1.97ms → 차이 +4.36ms(3.2배)
- PostgreSQL / unindexed / k=100: CAPTURED 중앙값 7.98ms, UNCAPTURED 중앙값 2.23ms → 차이 +5.75ms(3.6배)
- PostgreSQL / unindexed / k=1000: CAPTURED 중앙값 19.88ms, UNCAPTURED 중앙값 3.21ms → 차이 +16.67ms(6.2배)
- PostgreSQL / unindexed / k=10000: CAPTURED 중앙값 140.93ms, UNCAPTURED 중앙값 26.16ms → 차이 +114.77ms(5.4배)

### 동시 작성자

- MySQL / indexed / UPDATE / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 18.39, 기준선 2.13, 잔여 락 보유 17.51 → 막힘(커밋까지)
- MySQL / indexed / UPDATE / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 2.32, 기준선 2.12, 잔여 락 보유 17.95 → 안 막힘
- MySQL / indexed / UPDATE / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 22.10, 기준선 2.07, 잔여 락 보유 20.71 → 막힘(커밋까지)
- MySQL / indexed / UNCAPTURED / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 3.64, 기준선 2.13, 잔여 락 보유 2.06 → 안 막힘
- MySQL / indexed / UNCAPTURED / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 2.35, 기준선 2.12, 잔여 락 보유 1.81 → 안 막힘
- MySQL / indexed / UNCAPTURED / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 3.56, 기준선 2.07, 잔여 락 보유 2.89 → 안 막힘
- MySQL / unindexed / UPDATE / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 17.30, 기준선 2.16, 잔여 락 보유 16.33 → 막힘(커밋까지)
- MySQL / unindexed / UPDATE / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 18.54, 기준선 2.11, 잔여 락 보유 17.42 → 막힘(커밋까지)
- MySQL / unindexed / UPDATE / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 17.11, 기준선 2.04, 잔여 락 보유 16.17 → 막힘(커밋까지)
- MySQL / unindexed / UNCAPTURED / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 3.07, 기준선 2.16, 잔여 락 보유 1.92 → 안 막힘
- MySQL / unindexed / UNCAPTURED / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 2.63, 기준선 2.11, 잔여 락 보유 2.12 → 안 막힘
- MySQL / unindexed / UNCAPTURED / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 2.66, 기준선 2.04, 잔여 락 보유 2.07 → 안 막힘
- PostgreSQL / indexed / UPDATE / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 17.01, 기준선 1.22, 잔여 락 보유 16.71 → 막힘(커밋까지)
- PostgreSQL / indexed / UPDATE / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.58, 기준선 1.35, 잔여 락 보유 16.03 → 안 막힘
- PostgreSQL / indexed / UPDATE / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.31, 기준선 1.08, 잔여 락 보유 16.92 → 안 막힘
- PostgreSQL / indexed / UNCAPTURED / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.54, 기준선 1.22, 잔여 락 보유 1.21 → 안 막힘
- PostgreSQL / indexed / UNCAPTURED / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.48, 기준선 1.35, 잔여 락 보유 1.13 → 안 막힘
- PostgreSQL / indexed / UNCAPTURED / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.27, 기준선 1.08, 잔여 락 보유 1.01 → 안 막힘
- PostgreSQL / unindexed / UPDATE / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 17.73, 기준선 1.57, 잔여 락 보유 17.45 → 막힘(커밋까지)
- PostgreSQL / unindexed / UPDATE / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.65, 기준선 1.38, 잔여 락 보유 16.32 → 안 막힘
- PostgreSQL / unindexed / UPDATE / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.69, 기준선 1.09, 잔여 락 보유 18.41 → 안 막힘
- PostgreSQL / unindexed / UNCAPTURED / update_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.97, 기준선 1.57, 잔여 락 보유 1.70 → 안 막힘
- PostgreSQL / unindexed / UNCAPTURED / update_out_of_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.50, 기준선 1.38, 잔여 락 보유 1.14 → 안 막힘
- PostgreSQL / unindexed / UNCAPTURED / insert_in_range: SUCCESS, SUCCESS, SUCCESS, 대기 중앙값 1.86, 기준선 1.09, 잔여 락 보유 1.67 → 안 막힘

### MySQL 간격 잠금 가설

- MySQL / indexed / UPDATE: 대상 범위 안 INSERT는 변경이 커밋될 때까지 막혔다 → 간격 잠금이 관측됐다
- MySQL / indexed / UNCAPTURED: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다
- MySQL / unindexed / UPDATE: 대상 범위 안 INSERT는 변경이 커밋될 때까지 막혔다 → 간격 잠금이 관측됐다
- MySQL / unindexed / UNCAPTURED: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다
- PostgreSQL / indexed / UPDATE: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다
- PostgreSQL / indexed / UNCAPTURED: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다
- PostgreSQL / unindexed / UPDATE: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다
- PostgreSQL / unindexed / UNCAPTURED: 대상 범위 안 INSERT는 막히지 않았다 → 간격 잠금이 관측되지 않았다

### 조건 인덱스가 대상 밖 행에 미치는 영향

- MySQL / indexed / UPDATE: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 2.32, 기준선 2.12) → 안 막힘
- MySQL / indexed / UNCAPTURED: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 2.35, 기준선 2.12) → 안 막힘
- MySQL / unindexed / UPDATE: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 18.54, 기준선 2.11) → 막힘(커밋까지)
- MySQL / unindexed / UNCAPTURED: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 2.63, 기준선 2.11) → 안 막힘
- PostgreSQL / indexed / UPDATE: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 1.58, 기준선 1.35) → 안 막힘
- PostgreSQL / indexed / UNCAPTURED: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 1.48, 기준선 1.35) → 안 막힘
- PostgreSQL / unindexed / UPDATE: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 1.65, 기준선 1.38) → 안 막힘
- PostgreSQL / unindexed / UNCAPTURED: 대상 밖 행 UPDATE는 SUCCESS, SUCCESS, SUCCESS (대기 중앙값 1.50, 기준선 1.38) → 안 막힘

## 한계

- 로컬 docker 컨테이너, 같은 호스트의 단일 클라이언트다 — 네트워크 지연이 없다. 실서비스의 대기 시간은 네트워크 왕복만큼 더 길다
- 락 보유 시간은 JDBC 프록시가 잰 값이라 드라이버가 문장을 보내기 전 준비 시간이 시작점에 포함된다. 커밋 완료 시각도 클라이언트가 커밋 응답을 받은 시각이다(서버 기준 커밋 시각이 아니다)
- 락 보유 시간에는 안전장치의 모든 단계가 들어 있다. PostgreSQL의 큰 값은 사본 조회뿐 아니라 변경 뒤 키 재조회(JdbcChangeRunner.selectByKeys, 키 100개를 OR로 묶음)의 비용까지 포함한 결과다 — 그 문장의 계획도 위 실행계획 표에 함께 실었다
- 조건 인덱스는 조건 열(amount)에 건다. 실험 계획서의 '인덱스 있음(grp에 인덱스)'을 그대로 쓰면 k=10·100을 만들 수 없다 — grp는 0~99 균등이라 어떤 범위 조건도 200의 배수 행만 잡는다. 계획서가 허용한 '인덱스 없는 열 amount로 범위 조건' 쪽을 양쪽 조합에 같은 모양으로 적용했다
- 인덱스 있음 조합을 전부 끝낸 뒤 인덱스 없음 조합을 재는 순서라, 버퍼 캐시가 데워진 상태에서 두 번째를 잰다
- 동시 작성자 측정은 조합마다 탐침 3회뿐이다. UNCAPTURED는 잠그는 문장이 끝난 뒤 커밋까지의 창이 짧아, 출발이 커밋 뒤로 밀린 무효 표본이 섞인다 — 무효는 표의 결과와 CSV에 그대로 남겼다
- 대상 밖 행 UPDATE 탐침의 기준선에는 커밋(디스크 fsync) 비용이 들어 있다. 이 실험의 대기 값은 '막혔다/안 막혔다'를 가르는 비교용이지 절대 지연이 아니다
- 사본 캡처·재조회가 쓰는 CPU·IO 비용은 여기서 따로 재지 않았다. 락 보유 시간만 잰다
- 실험 테이블은 실측 후 삭제된다(`@AfterAll`). sample의 기존 테이블은 건드리지 않는다
