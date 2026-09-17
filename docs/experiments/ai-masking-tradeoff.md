# E2: 마스킹 토글이 가리는 것과 AI 진단 정확도

이 문서와 `ai-masking-responses.jsonl`은 `AiMaskingTradeoffExperimentIT`가 실제 DB의 실행계획과 실제 모델 응답으로 생성한 결과다 — 숫자도 채점도 손으로 고치지 않는다. 실행: `DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*AiMaskingTradeoffExperimentIT'`

조건은 셋이다. A = SQL 원문 + 계획 원문(제품 기본값), B = SQL만 `?` 마스킹 + 계획 원문(토글을 켠 실제 동작), C = SQL 마스킹 + 계획의 작은따옴표 문자열만 `'?'`

## 실행 환경

| 기종 | 버전 | exp_mask_customers | exp_mask_orders |
|---|---|---|---|
| MySQL | 8.4.11 | 100000행(VIP 2000, GOLD 18000, NORMAL 80000) | 200000행(CANCELLED 2000, PAID 190000, REFUND 8000) |
| PostgreSQL | PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit | 100000행(NORMAL 80000, VIP 2000, GOLD 18000) | 200000행(CANCELLED 2000, PAID 190000, REFUND 8000) |

- 실행 일시: 2026-09-17T00:09:40.054844+09:00
- 모델: 설정값 `claude-opus-4-8` (CLI 모드는 제품이 --model을 넘기지 않으므로 실제 모델은 CLI 기본값), effort=high, max_tokens=8192, 백엔드=cli
- 호출: 36회 = 사례 12 × 조건 3, 조건 비율 A 12회 B 12회 C 12회 
- 오류(정확도 계산에서 제외): A 0건 B 0건 C 0건 
- 사례 세트: `src/test/resources/experiments/ai-masking-cases.json` (L=리터럴이 진단을 가르는 사례 8개, C=리터럴과 무관한 대조군 4개)
- 프롬프트는 제품 `AiAnalysisRunner.run`과 글자 단위로 같다(`프롬프트가_제품_AiAnalysisRunner와_글자_단위로_같다` 테스트가 사례 12개 × 조건 A·B에서 확인)
- 노출 수는 프롬프트 문자열에서 민감 리터럴이 나온 횟수(부분 문자열 기준)다. 숫자 리터럴은 다른 수의 일부로도 세어질 수 있다(예: `1012345678`은 `01012345678` 안에서도 잡힌다)

## 표 1: 민감 리터럴 노출 수 (프롬프트 안에서 나온 횟수)

| 사례 | 민감 리터럴 | A (SQL 원문) | B (SQL 마스킹) | C (SQL+계획 마스킹) |
|---|---|---|---|---|
| L1 | `01012345678`, `1012345678` | 3 | 1 | 1 | 
| L2 | `%@gmail.com`, `@gmail.com` | 4 | 2 | 0 | 
| L3 | `PAID` | 1 | 0 | 0 | 
| L4 | `190000` | 1 | 0 | 0 | 
| C1 | `kimab` | 2 | 1 | 0 | 
| C2 | `2026-01-01` | 2 | 1 | 0 | 
| L5 | `%@gmail.com`, `@gmail.com` | 4 | 2 | 0 | 
| L6 | `PAID` | 2 | 1 | 0 | 
| L7 | `137`, `281`, `499` | 6 | 3 | 0 | 
| L8 | `2000-01-01` | 2 | 1 | 0 | 
| C3 | `kim@exp.test` | 2 | 1 | 0 | 
| C4 | `VIP` | 2 | 1 | 0 | 
| **합계** |  | **31** | **14** | **1** | 

사례별 내역(리터럴 × 조건)

| 사례 | 리터럴 | A | B | C |
|---|---|---|---|---|
| L1 | `01012345678` | 1 | 0 | 0 |
| L1 | `1012345678` | 2 | 1 | 1 |
| L2 | `%@gmail.com` | 2 | 1 | 0 |
| L2 | `@gmail.com` | 2 | 1 | 0 |
| L3 | `PAID` | 1 | 0 | 0 |
| L4 | `190000` | 1 | 0 | 0 |
| C1 | `kimab` | 2 | 1 | 0 |
| C2 | `2026-01-01` | 2 | 1 | 0 |
| L5 | `%@gmail.com` | 2 | 1 | 0 |
| L5 | `@gmail.com` | 2 | 1 | 0 |
| L6 | `PAID` | 2 | 1 | 0 |
| L7 | `137` | 2 | 1 | 0 |
| L7 | `281` | 2 | 1 | 0 |
| L7 | `499` | 2 | 1 | 0 |
| L8 | `2000-01-01` | 2 | 1 | 0 |
| C3 | `kim@exp.test` | 2 | 1 | 0 |
| C4 | `VIP` | 2 | 1 | 0 |

## 표 2: 자동 채점 (acceptKeywords 중 하나라도 포함하면 정답)

| 사례 | 기종 | 정답 원인(판단 기준 문서) | A | B | C |
|---|---|---|---|---|---|
| L1 | MySQL | 암시적 형변환 (문자열 열에 숫자 리터럴) | O | O | O | 
| L2 | MySQL | 앞 와일드카드 | O | O | O | 
| L3 | MySQL | 낮은 선택도: 풀스캔이 정상 | O | O | O | 
| L4 | MySQL | 깊은 OFFSET: 앞 행을 읽고 버림 | O | O | O | 
| C1 | MySQL | 컬럼에 함수 | O | O | O | 
| C2 | MySQL | 복합 인덱스 선두 누락 | O | O | O | 
| L5 | PostgreSQL | 앞 와일드카드 | O | O | O | 
| L6 | PostgreSQL | 낮은 선택도: 풀스캔이 정상 | O | O | O | 
| L7 | PostgreSQL | IN 목록 과다 | O | O | O | 
| L8 | PostgreSQL | 낮은 선택도(전 행): 풀스캔이 정상 | O | O | O | 
| C3 | PostgreSQL | 컬럼에 함수 | O | O | O | 
| C4 | PostgreSQL | 조인 키 인덱스 없음 | O | O | O | 
| **L 사례 정답률(8)** |  |  | **100%** (8/8) | **100%** (8/8) | **100%** (8/8) |
| **C 사례 정답률(4)** |  |  | **100%** (4/4) | **100%** (4/4) | **100%** (4/4) |

- `O`=정답, `X`=오답, `-`=오류·빈 응답이라 채점 제외. 위 정답률은 채점한 회차만 분모로 센다(괄호 안이 그 수)
- **자동 채점**이다 — 키워드 포함 여부만 본다. 최종 정확도는 사람 검토 후 확정한다(부록)

## 계획에 리터럴이 찍혔는가 (사례별 원문 발췌)

- **L1** (MySQL) 계획에 있는 민감 리터럴: `1012345678`
  - `{ "query_block": { "select_id": 1, "cost_info": { "query_cost": "10105.75" }, "table": { "table_name": "exp_mask_customers", "access_type": "ALL", "possible_key…`
- **L2** (MySQL) 계획에 있는 민감 리터럴: `%@gmail.com`, `@gmail.com`
  - `…": [ "id", "email" ], "attached_condition": "(`sample`.`exp_mask_customers`.`email` like '%@gmail.com')" } } }`
- **L3** (MySQL) 계획에 있는 민감 리터럴: 없음
  - `{ "query_block": { "select_id": 1, "cost_info": { "query_cost": "10489.95" }, "table": { "table_name": "exp_mask_orders", "access_type": "ref", "possible_keys":…`
- **L4** (MySQL) 계획에 있는 민감 리터럴: 없음
  - `{ "query_block": { "select_id": 1, "cost_info": { "query_cost": "20138.65" }, "ordering_operation": { "using_filesort": false, "table": { "table_name": "exp_mas…`
- **C1** (MySQL) 계획에 있는 민감 리터럴: `kimab`
  - `…"id", "email" ], "attached_condition": "(left(`sample`.`exp_mask_customers`.`email`,5) = 'kimab')" } } }`
- **C2** (MySQL) 계획에 있는 민감 리터럴: `2026-01-01`
  - `…, "created_at" ], "attached_condition": "(`sample`.`exp_mask_orders`.`created_at` >= DATE'2026-01-01')" } } }`
- **L5** (PostgreSQL) 계획에 있는 민감 리터럴: `%@gmail.com`, `@gmail.com`
  - `…"Total Cost": 2269.00, "Plan Rows": 21212, "Plan Width": 8, "Filter": "((email)::text ~~ '%@gmail.com'::text)" } } ]`
- **L6** (PostgreSQL) 계획에 있는 민감 리터럴: `PAID`
  - `…otal Cost": 3975.00, "Plan Rows": 190233, "Plan Width": 12, "Filter": "((status)::text = 'PAID'::text)" } } ]`
- **L7** (PostgreSQL) 계획에 있는 민감 리터럴: `137`, `281`, `499`
  - `…4,115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,130,131,132,133,134,135,136,137,138,139,140,141,142,143,144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,1…`
- **L8** (PostgreSQL) 계획에 있는 민감 리터럴: `2000-01-01`
  - `…, "Total Cost": 2269.00, "Plan Rows": 100000, "Plan Width": 8, "Filter": "(created_at >= '2000-01-01'::date)" } } ]`
- **C3** (PostgreSQL) 계획에 있는 민감 리터럴: `kim@exp.test`
  - `…al Cost": 2519.00, "Plan Rows": 500, "Plan Width": 8, "Filter": "(lower((email)::text) = 'kim@exp.test'::text)" } } ]`
- **C4** (PostgreSQL) 계획에 있는 민감 리터럴: `VIP`
  - `… "Total Cost": 2269.00, "Plan Rows": 2057, "Plan Width": 27, "Filter": "((grade)::text = 'VIP'::text)" } ] } ] } } ]`

## 부록: 응답 앞 300자 (사람 검토용)

### L1 — 암시적 형변환 (문자열 열에 숫자 리터럴)

- **A** (자동 채점 정답): **가장 유력한 원인은 암시적 형변환입니다.** 옵티마이저는 `exp_mask_customers_phone_idx`를 후보(possible_keys)로 보고도 `access_type=ALL`로 테이블 전체(추정 99,855행)를 읽었습니다. 게다가 `attached_condition`에 비교값이 따옴표 없는 숫자 `1012345678`로 들어가 있어, 판단 기준의 "컬럼 타입과 비교값 타입 불일치" 신호와 맞습니다. `phone`이 문자열 컬럼이면 MySQL은 비교할 때 컬럼 쪽을 숫자로 바꿉니다. 인덱스 컬럼에 함수를 씌운 것과 …
- **B** (자동 채점 정답): **가장 유력한 원인은 암시적 형변환입니다.** `possible_keys`에 `exp_mask_customers_phone_idx`가 있는데도 `access_type=ALL`로 약 99,855행을 풀스캔하고 있고, `attached_condition`을 보면 비교값이 따옴표 없는 숫자 `1012345678`입니다. 판단 기준 문서에 따르면 문자열 컬럼을 숫자와 비교하면 컬럼 쪽이 숫자로 캐스팅됩니다. 컬럼에 함수를 씌운 것과 같아서 인덱스로 찾지 못하고 전체를 훑게 됩니다. 이 경우 행수가 수백 행 수준이 아니라서 "작은 테이블이…
- **C** (자동 채점 정답): **가장 유력한 원인은 암시적 형변환입니다.** 인덱스 `exp_mask_customers_phone_idx`가 `possible_keys`에 있는데도 옵티마이저는 `access_type=ALL`(풀스캔)을 골랐습니다. `attached_condition`을 보면 비교값이 따옴표 없는 숫자 `1012345678`입니다. `phone` 컬럼이 문자열 타입이라면 이는 문서의 "VARCHAR 컬럼에 INT 파라미터" 패턴에 해당합니다. 이 경우 컬럼이 숫자로 캐스팅되어 인덱스로 찾아가지 못하고 전체를 훑게 됩니다. 추정으로만 약 99,8…

### L2 — 앞 와일드카드

- **A** (자동 채점 정답): **가장 유력한 원인은 앞 와일드카드 LIKE(`'%@gmail.com'`)입니다.** B+Tree는 선두 문자부터 시작점을 잡는데, 이 패턴은 뒤쪽만 고정돼 있어 시작점을 못 잡습니다. 그래서 `exp_mask_customers_email_idx`를 seek하지 못하고 인덱스 리프를 처음부터 끝까지 훑고 있습니다(`access_type=index`, `rows_examined_per_scan: 99855`). 이는 판단 기준의 근본원인 5종 중 "복합 인덱스 선두 누락 / 앞 와일드카드"에 해당합니다. 규칙 기반 지적처럼 범위 조…
- **B** (자동 채점 정답): **가장 유력한 원인은 앞에 와일드카드가 붙은 LIKE입니다.** `attached_condition`의 조건이 `email like '%@gmail.com'`이라서 B+Tree가 시작점을 잡을 수 없습니다. 판단 기준 5종 가운데 "복합 인덱스 선두 누락 / 앞 와일드카드"에 해당하고, 그래서 `exp_mask_customers_email_idx` 인덱스 전체(`rows_examined_per_scan` 99,855)를 훑는 `access_type=index`가 나왔습니다. 다만 `using_index=true`이고 `used_c…
- **C** (자동 채점 정답): **판정:** 인덱스 풀스캔(`access_type=index`)입니다. 가장 유력한 원인은 앞 와일드카드 LIKE(`'%x'`, `'%x%'`)로 B+Tree가 시작점을 잡지 못하는 것이지만, 이 플랜만으로는 확정할 수 없습니다. `exp_mask_customers_email_idx`를 쓰긴 하는데 범위 탐색이 아니라 인덱스 리프를 처음부터 끝까지 훑고 있고, 스캔 추정 행수가 99,855행입니다. `attached_condition`에 패턴이 `'?'`로 가려져 있어서, 실제 바인딩 값이 `%`로 시작하는지는 판단 근거가 없어 …

### L3 — 낮은 선택도: 풀스캔이 정상

- **A** (자동 채점 정답): 규칙상 비효율 신호는 없습니다. `access_type=ref`이고 `exp_mask_orders_status_idx`를 `status` 상수 조건(`ref: const`)으로 타고 있어서, 기준 문서의 MySQL 표에 있는 ALL·index·filesort·temporary 중 어느 것에도 해당하지 않습니다. 다만 이 계획에서 추정 읽기 행수(`rows_examined_per_scan`)는 99,852건이고 `filtered`는 100.00입니다. 비용도 `eval_cost` 9985.20이 `read_cost` 504.75보다 …
- **B** (자동 채점 정답): 1. **확실한 것:** 이 실행계획에는 판단 기준 문서의 MySQL 비효율 신호(access_type=ALL/index, using_filesort, using_temporary_table)가 없습니다. `exp_mask_orders_status_idx`를 `ref`(const)로 타고 있으니 규칙 기반 판정 "신호 없음"은 맞습니다. 인덱스를 쓰고 있으므로 인덱스를 못 타게 하는 근본원인 5종(암시적 형변환 등)도 이 플랜에서는 보이지 않습니다. 2. **주의할 점:** 추정으로 읽는 행이 `rows_examined_per_sc…
- **C** (자동 채점 정답): 규칙 기반 판정대로 풀스캔(access_type=ALL), filesort, 임시 테이블 신호는 없습니다. `exp_mask_orders_status_idx` 인덱스를 `ref`(`status` 상수 비교)로 정상적으로 타고 있습니다. 다만 인덱스로 찾아 읽는 행이 `rows_examined_per_scan` 기준 99,852행으로 추정되고, `filtered`가 100.00이라 걸러지는 행이 없습니다. 판단 기준의 선택도 원칙으로 보면, 이 행수가 테이블 전체에서 큰 비중이면 인덱스로 랜덤 접근하는 것보다 순차로 전체를 읽는 편이…

### L4 — 깊은 OFFSET: 앞 행을 읽고 버림

- **A** (자동 채점 정답): 1. 규칙이 잡은 신호는 `access_type=index`(인덱스 풀스캔)이지만, 이 쿼리는 판단 기준의 예외에 해당합니다. `key=PRIMARY`이고 `using_index=true`인 커버링 인덱스라 테이블 본문을 읽지 않으며, 기준 문서에 따르면 테이블 풀스캔보다 나을 수 있습니다. `using_filesort=false`이니 ORDER BY도 인덱스 순서로 처리돼 별도 정렬이 없습니다. 2. 쿼리에 WHERE 절이 없으므로 규칙 문구의 "범위 조건 검토"는 들어맞지 않습니다. 인덱스 무력화 근본원인 5종(암시적 형변환, …
- **B** (자동 채점 정답): **가장 유력한 판단:** 이 실행계획은 규칙이 지적한 `access_type=index`(인덱스 풀스캔)에 해당하지만, 판단 기준 문서의 예외인 "커버링 인덱스라 테이블 접근이 없으면 풀스캔보다 나을 수 있다"에 들어맞습니다. `key=PRIMARY`, `using_index=true`, `used_columns=[id]`이므로 테이블 행을 읽지 않고 인덱스만으로 결과를 만듭니다. `using_filesort=false`이므로 ORDER BY도 PK 인덱스 순서로 처리돼 별도 정렬이 없습니다. 따라서 기준 문서상으로는 인덱스 자체…
- **C** (자동 채점 정답): **판정: 문서 기준으로 이 플랜을 비효율로 단정할 근거는 약합니다.** `access_type=index`는 인덱스 풀스캔 신호가 맞습니다. 하지만 `key=PRIMARY`, `using_index=true`, `used_columns`가 `id` 하나라서 테이블에 접근하지 않는 커버링 인덱스입니다. 문서도 이 경우를 "풀스캔보다 나을 수 있다"는 예외로 둡니다. 또 `using_filesort=false`이므로 ORDER BY는 PK 순서로 처리되고, 별도 정렬도 없습니다. **확인할 것:** 이 플랜은 추정치만 보여 줍니다(`…

### C1 — 컬럼에 함수

- **A** (자동 채점 정답): 가장 유력한 원인은 **컬럼에 함수를 씌운 것**입니다. `LEFT(email, 5) = 'kimab'`처럼 인덱스 컬럼 `email`을 함수 안에 넣어서, 옵티마이저가 B+Tree에서 탐색 시작점을 잡지 못합니다. 그래서 `exp_mask_customers_email_idx`를 처음부터 끝까지 훑는 `access_type=index`가 됐고, 추정 스캔 행수(rows_examined_per_scan)는 99,855행입니다. `using_index=true`라서 테이블에는 가지 않는 커버링 인덱스 스캔이므로 테이블 풀스캔보다는 낫습…
- **B** (자동 채점 정답): **판정: 근본원인은 "컬럼에 함수/표현식"입니다.** WHERE 절이 `LEFT(email, 5) = 'kimab'`처럼 인덱스 컬럼을 함수로 감쌌습니다. 옵티마이저는 함수 결과를 미리 알 수 없어서, `exp_mask_customers_email_idx`가 있는데도 시작점을 잡지 못합니다. 그래서 규칙이 지적한 인덱스 풀스캔(access_type=index)이 나왔고, rows_examined_per_scan 99,855로 인덱스 리프를 처음부터 끝까지 읽고 있습니다. `using_index=true`라 테이블 접근이 없는 커버…
- **C** (자동 채점 정답): **원인은 인덱스 컬럼에 함수를 씌운 것입니다.** `WHERE LEFT(email, ?) = ?`가 판단 기준의 "컬럼에 함수/표현식"에 그대로 해당합니다. 옵티마이저는 `LEFT(email, 5)`의 결과를 미리 알 수 없어서 `exp_mask_customers_email_idx`로 시작점을 잡지 못합니다. 그래서 `access_type=index`로 인덱스 전체(추정 99,855행)를 훑고, 그 뒤에 `attached_condition`으로 거릅니다. `using_index=true`이고 `used_columns`가 `id`와…

### C2 — 복합 인덱스 선두 누락

- **A** (자동 채점 정답): 가장 유력한 원인은 **복합 인덱스 선두 컬럼 누락**입니다. 사용된 인덱스 `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데 WHERE 절에는 `created_at`만 있습니다. B+Tree는 선두 컬럼부터 시작점을 잡으므로 범위 탐색을 못 하고, 인덱스 리프 전체를 훑는 `access_type=index`가 됐습니다. 다만 `using_index=true`라 테이블 접근이 없는 커버링 인덱스 스캔입니다. 판단 기준 문서의 예외대로 테이블 풀스캔보다는 나을 수 있…
- **B** (자동 채점 정답): 가장 유력한 원인은 **복합 인덱스의 선두 컬럼이 조건에 없는 것**입니다. `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데 WHERE 절에는 `created_at`만 있습니다. 판단 기준의 "B+Tree는 선두 컬럼부터 시작점을 잡는다" 원칙에 따라 옵티마이저는 범위 탐색을 못 하고 인덱스 전체를 훑습니다(access_type=index, rows_examined_per_scan 199,704). 다만 `using_index=true`이고 필요한 컬럼이 `id`…
- **C** (자동 채점 정답): **가장 유력한 원인은 복합 인덱스의 선두 컬럼이 조건에 없는 것입니다.** 쓰인 인덱스 `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데, WHERE 조건은 뒤쪽 컬럼인 `created_at`만 걸려 있습니다. B+Tree는 선두 컬럼부터 시작점을 잡기 때문에 `created_at >= ?`만으로는 시작점을 못 잡습니다. 그래서 인덱스 리프를 처음부터 끝까지 읽는 `access_type=index`가 나왔습니다. 추정으로는 199,704행을 훑어 66,561행을 …

### L5 — 앞 와일드카드

- **A** (자동 채점 정답): 가장 유력한 원인은 **앞 와일드카드 LIKE**(`LIKE '%@gmail.com'`)입니다. 판단 기준의 "복합 인덱스 선두 누락 / 앞 와일드카드" 원인에 해당합니다. B+Tree는 정렬된 값에서 시작점을 잡아야 하는데, 뒤만 고정된 패턴은 시작점을 잡을 수 없어 Seq Scan으로 떨어집니다. 그래서 `email` 컬럼에 일반 B-Tree 인덱스를 추가해도 이 조건은 해결되지 않을 가능성이 높습니다. 규칙 기반 지적처럼 "WHERE 조건에 맞는 인덱스 검토"만으로는 부족합니다. 다만 이 계획은 추정치만 있는 일반 EXPLAI…
- **B** (자동 채점 정답): 가장 유력한 원인은 필터 `email ~~ '%@gmail.com'`가 **앞 와일드카드 LIKE**라는 점입니다. B+Tree는 정렬 순서에서 시작점을 잡아야 하는데 앞쪽이 고정되지 않았으니, `email`에 인덱스가 있어도 쓰지 못하고 Seq Scan으로 테이블 전체를 읽게 됩니다. 이것은 판단 기준의 근본원인 중 "복합 인덱스 선두 누락 / 앞 와일드카드"에 해당합니다. 다만 이 계획은 추정값만 있는 EXPLAIN입니다. 옵티마이저는 21,212행이 나올 것으로 추정했고 Total Cost는 2269.00이지만, 실제 행수가 …
- **C** (자동 채점 정답): **PostgreSQL 1차 분석** 실행계획은 `exp_mask_customers` 전체를 읽는 Seq Scan이고 추정 행수는 21,212입니다. 기준 문서상 이 신호는 인덱스 부재나 앞 와일드카드 LIKE를 의심할 근거이지만, 작은 테이블이거나 조건에 맞는 행이 테이블 대부분이면 플래너가 일부러 고르는 정상 선택일 수도 있습니다. Filter가 `'?'::text`로 찍힌 것으로 보아 실제 바인딩 값이 아니라 자리표시자 `?` 문자열 자체로 EXPLAIN을 돌린 것으로 보입니다. 그래서 실제 패턴이 앞 와일드카드(`'%x'`)…

### L6 — 낮은 선택도: 풀스캔이 정상

- **A** (자동 채점 정답): 규칙대로 Seq Scan은 맞지만, 이 계획만으로는 비효율이라고 판정할 수 없습니다. 이 결과는 추정치만 있는 일반 EXPLAIN이라 실제 행수와 테이블 전체 행수가 없고, 판단 기준의 최종 판단자인 선택도를 계산할 수 없습니다. 추정 반환 행수(Plan Rows)가 190,233으로 작은 결과가 아닙니다. 기준 문서에 따르면 `status = 'PAID'`가 테이블 대부분에 해당할 때 플래너는 일부러 Seq Scan을 고르고, 이는 정상입니다. 따라서 인덱스를 바로 추가하기 전에 두 가지를 확인해야 합니다. 첫째, 전체 행수와 비…
- **B** (자동 채점 정답): 이 계획만으로는 인덱스가 없어서 생긴 문제인지, 정상적인 풀스캔인지 판정할 수 없습니다. `exp_mask_orders` 전체를 Seq Scan으로 읽으면서 `status = 'PAID'`로 거르고 있고, 옵티마이저는 결과를 190,233행(Plan Rows)으로 추정했습니다. 판단 기준 문서에 따르면 Seq Scan의 좋고 나쁨은 선택도가 정합니다. 테이블 전체 행수가 계획에 없어서 190,233행이 전체의 몇 %인지 알 수 없습니다. 'PAID'가 테이블의 대부분이면 플래너가 일부러 고른 정상 풀스캔이고, 일부라면 `status…
- **C** (자동 채점 정답): **가장 유력한 원인:** 실행계획은 `exp_mask_orders` 전체를 읽는 Seq Scan이고, 추정 행수(Plan Rows)는 190,233입니다. 하지만 판단 기준상 작은 테이블이거나 조건에 맞는 행이 테이블 대부분일 때는 Seq Scan이 정상이고 더 빠를 수도 있습니다. 전체 행수와 `status`의 선택도가 나와 있지 않아서, 인덱스가 없어서인지 선택도가 낮아 플래너가 일부러 골랐는지 지금은 알 수 없습니다. **판단을 막는 두 가지:** - 이 계획은 ANALYZE 없는 추정 계획이라 실제 행수(Actual Row…

### L7 — IN 목록 과다

- **A** (자동 채점 정답): **판정: 판단 기준 문서에 걸리는 비효율 신호는 없습니다.** 실행계획은 기본키 인덱스 `exp_mask_orders_pkey`를 쓰는 `Index Only Scan` 한 단계뿐입니다. PostgreSQL 규칙의 신호인 `Seq Scan`, `Nested Loop` 안쪽 `Seq Scan`, `Sort Method: external`은 하나도 없습니다. `IN` 목록 500개는 `Index Cond`의 `id = ANY(...)`로 바뀌어 인덱스에서 바로 찾으므로, 규칙 기반 지적과 판정이 같습니다. 다만 이건 `ANALYZE` …
- **B** (자동 채점 정답): 판단 기준에 비춰 보면 이 실행계획에는 비효율 신호가 없습니다. PK 인덱스(`exp_mask_orders_pkey`)로 Index Only Scan을 하고 있어서, 문서가 PostgreSQL 신호로 드는 Seq Scan, Nested Loop 안쪽 Seq Scan, Sort Method: external이 하나도 없습니다. `IN` 목록 500개는 `id = ANY(...)` 인덱스 조건으로 들어갔고, 인덱스 무력화 원인 5종도 보이지 않습니다. 비교값이 `bigint[]`로 들어가 암시적 형변환이 없고, 컬럼에 함수가 씌워지지 …
- **C** (자동 채점 정답): 이 실행계획에서는 판단 기준 문서에 있는 비효율 신호(Seq Scan, Nested Loop 안쪽 Seq Scan, Sort Method: external)가 보이지 않습니다. 기본키 인덱스 `exp_mask_orders_pkey`를 쓰는 Index Only Scan이라 테이블을 읽지 않고 인덱스만으로 끝나며, 현재 plan상 가장 유력한 원인은 없고 규칙 기반 판정("신호 없음")과도 일치합니다. 다만 이 plan은 `EXPLAIN ANALYZE`가 아니어서 Actual Rows가 없습니다. 그래서 추정 행수(Plan Rows 5…

### L8 — 낮은 선택도(전 행): 풀스캔이 정상

- **A** (자동 채점 정답): **판정:** Seq Scan이 나왔지만 인덱스가 없어서라기보다, 판단 기준의 "낮은 선택도" 예외에 해당할 가능성이 큽니다. 플래너는 필터 `created_at >= '2000-01-01'`을 거친 결과가 100,000행(Plan Rows)이라고 추정했습니다. 이 조건이 테이블의 대부분을 걸러내지 못한다고 본 것이고, 그렇다면 인덱스로 랜덤 접근하는 것보다 전체를 순차로 읽는 편이 싸서 일부러 Seq Scan을 골랐다고 보는 게 기준에 맞습니다. 다만 이 계획은 `EXPLAIN ANALYZE`가 아니라서 Actual Rows가 없…
- **B** (자동 채점 정답): **가장 유력한 원인:** 규칙 기반 지적대로 Seq Scan은 맞습니다. 다만 판단 기준 문서의 "낮은 선택도" 예외에 해당할 가능성이 커서, 인덱스 부재라고 단정하기는 어렵습니다. 옵티마이저는 조건 `created_at >= '2000-01-01'`을 통과할 행을 100000행(Plan Rows)으로 추정했습니다. 2000년 이후라는 넓은 범위라 대부분의 행이 걸린다고 보고 일부러 순차 스캔을 골랐을 수 있습니다. 하지만 이 계획에는 테이블 전체 행수와 실제 행수(Actual Rows)가 없습니다. 그래서 선택도가 실제로 낮은지…
- **C** (자동 채점 정답): **가장 유력한 원인:** `exp_mask_customers` 전체를 읽는 Seq Scan이 나왔습니다. 다만 판단 기준에 따르면 Seq Scan은 작은 테이블이거나 결과가 테이블 대부분일 때(선택도가 낮을 때) 정상이고, 플래너가 일부러 고르기도 합니다. 이 계획의 필터 후 추정 행수(Plan Rows)는 100,000입니다. 이 값이 테이블 전체 행수와 비슷하다면 `created_at >= ?` 조건이 거의 걸러내지 못한다는 뜻이라 인덱스 문제로 단정할 수 없습니다. 그런데 계획에는 테이블 전체 행수가 없어서 둘 중 어느 쪽인…

### C3 — 컬럼에 함수

- **A** (자동 채점 정답): **가장 유력한 원인은 인덱스 컬럼에 함수를 씌운 것입니다.** 판단 기준의 "컬럼에 함수/표현식" 항목에 해당하고, 규칙이 짚은 Seq Scan은 그 결과로 보입니다. 조건이 `lower(email) = 'kim@exp.test'`라서 `email`이 함수 안에 들어가 있고, 이러면 옵티마이저가 표현식 결과를 미리 알 수 없어 `email`에 일반 인덱스가 있더라도 쓸 수 없습니다. 기준 문서가 제시하는 해법은 함수를 상수 쪽으로 옮기거나 함수 기반 인덱스를 만드는 것입니다. PostgreSQL이라면 `lower(email)` 표…
- **B** (자동 채점 정답): **가장 유력한 원인은 컬럼에 함수를 씌운 조건 `lower(email) = ?`입니다.** 판단 기준의 근본원인 5종 중 "컬럼에 함수/표현식"에 해당합니다. 옵티마이저는 표현식의 결과를 미리 알 수 없어서, `email`에 일반 인덱스가 있어도 쓰지 못하고 Seq Scan(총 비용 2519.00, 추정 500행)을 골랐을 가능성이 큽니다. 기준 문서의 해법은 함수를 상수 쪽으로 옮기거나 함수 기반 인덱스를 두는 것입니다. PostgreSQL에서는 `(lower(email))` 표현식 인덱스가 여기에 해당합니다. 다만 지금 계획은…
- **C** (자동 채점 정답): 가장 유력한 원인은 **컬럼에 함수를 씌운 조건**입니다. WHERE 절이 `lower(email) = ?`여서 판단 기준 문서의 "인덱스 무력화 근본원인 5종" 중 "컬럼에 함수/표현식"에 해당하고, 규칙 기반 지적의 Seq Scan은 그 결과로 나온 증상으로 봐야 합니다. 해법은 두 가지입니다. 비교값을 애플리케이션에서 미리 소문자로 바꿔 함수를 상수 쪽으로 옮기거나, `lower(email)` 표현식 인덱스(함수 기반 인덱스)를 만드는 것입니다. 그 전에 `email` 컬럼에 일반 인덱스만 있는지 스키마에서 확인해 보세요. 다…

### C4 — 조인 키 인덱스 없음

- **A** (자동 채점 정답): 1. **가장 유력한 원인:** 두 테이블 모두 Seq Scan입니다. `exp_mask_customers`는 `grade = 'VIP'` 조건을 Filter로 걸러 2,057행으로 추정되고, `exp_mask_orders`는 조건 없이 200,000행 전체를 읽는 것으로 추정됩니다. 따라서 `grade`에 맞는 인덱스가 없을 가능성을 먼저 의심합니다. 다만 규칙 문서는 작은 테이블의 Seq Scan을 정상으로 봅니다. 이 계획에는 customers 전체 행수가 없어서 선택도를 알 수 없고, 비효율이라고 단정할 수 없습니다. 2. …
- **B** (자동 채점 정답): **판단:** 규칙상 가장 유력한 원인은 두 테이블 모두 인덱스를 쓰지 않는다는 점입니다. `exp_mask_customers`는 `grade = 'VIP'` 필터로 Seq Scan을 하고(추정 2,057행), `exp_mask_orders`는 필터 없이 추정 200,000행을 전부 읽어 Hash Join에 넣습니다. 다만 조인이 Nested Loop가 아니라 Hash Join이므로 "Nested Loop + 안쪽 Seq Scan" 같은 조인 폭발 신호에는 해당하지 않습니다. 최종 결과 추정은 4,114행으로 orders 추정치의 …
- **C** (자동 채점 정답): 이 계획에서 가장 유력한 신호는 `exp_mask_customers`의 `grade` 필터가 인덱스 없이 Seq Scan으로 처리되는 점입니다(추정 2,057행). 다만 조인은 Nested Loop가 아니라 Hash Join이라서, 판단 기준이 더 강한 신호로 보는 "Nested Loop 안쪽 Seq Scan(조인 폭발)"에는 해당하지 않습니다. `exp_mask_orders`의 Seq Scan(추정 200,000행)은 필터 없이 해시 조인의 바깥 입력으로 전체를 읽는 모양입니다. 이것만으로는 인덱스 부재라고 단정할 수 없습니다. …

## 한계

- 사례 12개, 조건당 1회 호출(반복 없음)이다. 같은 조건이라도 호출마다 응답이 달라질 수 있는데 이 실험은 그 분산을 재지 않는다
- 자동 채점은 키워드 포함 여부만 본다. 키워드를 우연히 포함한 오답도 정답으로, 표현이 다른 정답도 오답으로 셀 수 있다
- CLI 모드(claude CLI headless)로만 쟀다. API 경로와 프롬프트·판단 기준은 같지만 모델·샘플링은 다를 수 있고, CLI 모드에서 제품은 --model을 넘기지 않는다
- 조건 C는 실험 장치다 — 제품에 있는 기능이 아니라, 계획까지 가렸다면 무엇이 남는지 보려고 실행기가 계획 문자열의 작은따옴표 리터럴만 바꿔 넣은 것이다. 계획에 작은따옴표로 안 찍힌 값(숫자)은 그대로 남는다
- 노출은 프롬프트 문자열의 부분 문자열 세기다. 계획추정치·비용에 우연히 들어간 숫자도 세어질 수 있다
- 정확도의 차이는 조건 B/C에서 리터럴이 빠져서인지, 계획 문자열이 달라져서인지 이 실험만으로는 가르지 못한다
- 실행계획은 제품과 같은 경로(`DbmsOperator.explain`)로 얻은 원문이다(MySQL `EXPLAIN FORMAT=JSON`, PostgreSQL `EXPLAIN (FORMAT JSON)`)
