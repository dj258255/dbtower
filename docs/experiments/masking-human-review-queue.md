# #159 사람 판정 대기 — 판정자 셋이 갈린 38칸

판정자 셋(qwen3:14b, llama3.1:8b, qwen3:8b)이 다른 칸만 모았다. 126칸 중 38칸이다.

각 칸의 응답을 읽고 **사람이** `confirmed` / `candidate` / `missed` 중 하나를 정한다.
- `confirmed`: 원인을 확정적으로 지목했다
- `candidate`: 후보로만 언급하거나 유보했다
- `missed`: 원인을 짚지 못했다

정한 값은 마지막 `정답(사람):` 줄에 적으면 그걸로 일치율을 다시 낸다.

---

## 1. M3 / FULL / rep 2

판정: qwen3:14b: candidate | llama3.1:8b: confirmed | qwen3:8b: confirmed

응답:

```
**판정: 규칙에 걸리는 비효율 신호는 없습니다.** `access_type=range`로 `exp_mask_customers_email_idx`를 범위 탐색하고, 추정 111행(약 99,742행 중)만 읽습니다. `using_index=true`라 테이블에는 접근하지 않는 커버링 인덱스 조회입니다.

**확인할 것 1. 실제로 바인딩되는 LIKE 패턴.** `attached_condition`에 `like '?'`처럼 자리표시 문자열이 그대로 들어가 있습니다. 이 계획은 앞 와일드카드가 없는 값으로 뽑힌 것일 수 있습니다. 실제 값이 `'%abc'`나 `'%abc%'`면 B+Tree가 시작점을 못 잡습니다. 그러면 `index`(인덱스 풀스캔)나 `ALL`로 떨어질 수 있으니, 실제 값의 모양을 먼저 확인해야 합니다.

**확인할 것 2. 실제 읽은 행수.** 이 계획은 추정치만 있는 EXPLAIN이라 카디널리티 오추정은 판정할 수 없습니다. 괴리를 보려면 실제 바인딩 값으로 `EXPLAIN ANALYZE`(TREE)를 떠서 추정 111행과 `actual rows`를 비교하세요.

**모르는 것: 회귀의 원인.** 입력에 회귀 수치(rows/call 변화 등)가 없어서 이 계획만으로는 원인을 판단할 수 없습니다.
```
정답(사람): 

---

## 2. M3 / FULL / rep 3

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
이 실행계획에서는 규칙표의 비효율 신호(ALL·filesort·temporary·index)가 하나도 보이지 않습니다. `access_type=range`로 `exp_mask_customers_email_idx`를 사용했고 `using_index=true`라 테이블은 읽지 않았습니다. 추정 111행은 약 99,742행의 0.1% 수준이라, 판단 기준의 선택도 원칙에 따르면 인덱스 범위 스캔이 맞는 선택입니다.

다만 `attached_condition`이 `like '?'`로 찍혀 있습니다. 이는 실제 바인딩 값이 아니라 마스킹된 리터럴 `'?'`(와일드카드 없는 접두사)로 계획을 세웠을 가능성을 뜻합니다. 실제 파라미터가 `'%abc'`나 `'%abc%'` 같은 앞 와일드카드라면 B+Tree가 시작점을 잡지 못합니다. 그러면 운영에서는 이 계획과 달리 풀스캔이나 인덱스 풀스캔으로 떨어질 수 있습니다. 이것이 가장 먼저 의심할 지점입니다.

확인할 것은 두 가지입니다.
1. **실제 바인딩된 LIKE 패턴의 형태**: 접두사 고정인지, 앞 와일드카드인지 봐야 합니다.
2. **추정 행수와 실제 행수 비교**: 이 계획은 추정치만 담긴 EXPLAIN JSON이라 실제 행수와 비교할 수 없습니다. 실제 값으로 `EXPLAIN ANALYZE`(TREE)를 떠서 `rows`/`loops`를 확인해야 합니다.

회귀 판단에 필요한 rows/c …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 3. M5 / FULL / rep 1

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
**가장 유력한 원인은 복합 인덱스의 선두 컬럼이 조건에 없다는 점입니다.** 인덱스 `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데, WHERE 절에는 뒤쪽 컬럼인 `created_at`만 있습니다. B+Tree는 선두 컬럼부터 시작점을 잡으므로 `created_at` 범위로는 시작점을 잡지 못하고, 인덱스 전체를 훑는 `access_type=index`가 나왔습니다. 추정으로는 199,704행을 읽어 `filtered` 33.33%, 즉 66,561행을 돌려줍니다.

다만 `using_index=true`여서 인덱스만으로 결과를 만들고 테이블은 읽지 않습니다. 판단 기준 문서의 예외대로 테이블 풀스캔보다는 나을 수 있습니다. 또 이 수치는 전부 옵티마이저 추정입니다. 실제 선택도는 `EXPLAIN ANALYZE`(TREE 포맷)로 actual rows를 확인한 뒤에 판단해야 합니다. 결과가 테이블의 대부분이라면 지금 방식이 정상이고, 소수 행만 뽑는다면 선두 누락이 원인입니다.

**확인할 것:**
- describeSchema로 `created_at`이 선두인 인덱스가 따로 있는지 봅니다. 없고 선택도가 높다면 `created_at`을 선두로 하고 `amount`를 포함하는 커버링 인덱스를 검토합니다.
- 바인드 값이 `DATE'?'`로 가려져 있어, 파라미터 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 4. M5 / NONE / rep 2

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: missed

응답:

```
**가장 유력한 원인은 복합 인덱스 선두 컬럼 누락입니다.** 인덱스 `exp_mask_orders_amount_created_idx`는 (amount, created_at) 순서인데 WHERE 절은 두 번째 컬럼인 created_at만 조건으로 씁니다. 그래서 B+Tree에서 시작점을 잡지 못하고 `access_type=index`로 인덱스 리프 전체(rows_examined_per_scan 199,704, 테이블 행수와 같음)를 훑고 있습니다. 판단 기준 문서의 "복합 인덱스 선두 누락"에 해당합니다. 다만 `using_index=true`라서 테이블 접근 없이 인덱스만 읽는 커버링 형태이고, 문서 예외대로 테이블 풀스캔(ALL)보다는 나을 수 있습니다. `filtered 33.33`과 rows_produced 66,561은 옵티마이저의 추정치일 뿐입니다. 실제 선택도와 추정 대비 실제 괴리는 이 계획만으로 알 수 없습니다.

**확인할 것:**
1. describeSchema에서 created_at이 선두인 인덱스가 있는지 확인합니다. 없다면 (created_at) 또는 (created_at, amount) 인덱스를 검토합니다.
2. MySQL 8.4에서는 `EXPLAIN ANALYZE`(TREE 포맷)로 실제 rows를 확인합니다. `created_at >= '2026-08-01'`이 테이블의 소수만 뽑는지, 대부분을 뽑는지  …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 5. M5 / NONE / rep 3

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
**가장 유력한 원인은 복합 인덱스의 선두 컬럼이 조건에 없는 것입니다.** 인덱스 `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데 WHERE 절에는 뒷 컬럼 `created_at`만 있습니다. B+Tree가 시작점을 잡지 못해 `access_type=index`로 인덱스 리프를 처음부터 끝까지 읽고 있습니다. 실행계획에서도 `rows_examined_per_scan`이 199,704로 테이블 추정 행수 전체와 같습니다.

`using_index=true`라서 테이블에는 접근하지 않는 커버링 인덱스입니다. 판단 기준 문서의 예외대로 테이블 풀스캔(ALL)보다는 나을 수 있습니다. 그래도 범위 조건이 시작점을 잡는 상태는 아니므로 `created_at`을 선두로 하는 인덱스(예: `(created_at, amount)`)로 range 접근이 되는지 검토할 만합니다.

다만 `filtered=33.33`은 옵티마이저의 추정치일 뿐이고, 실제 선택도는 이 입력만으로는 알 수 없습니다. 먼저 `EXPLAIN ANALYZE`(TREE 포맷)로 추정 행수와 실제 행수를 비교해서 `created_at >= '2026-08-01'`이 실제로 전체의 몇 %를 반환하는지 확인해야 합니다. 대부분을 반환한다면 풀스캔이 정상 판단일 수 있습니다.

이번 입력에는 회귀 전 실행계획과 ro …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 6. M5 / STRUCTURE / rep 2

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
가장 유력한 원인은 판단 기준의 **복합 인덱스 선두 누락**이에요. 조건은 `created_at`에만 걸려 있는데, 사용한 인덱스 `exp_mask_orders_amount_created_idx`의 선두 컬럼은 `amount`예요. 그래서 B+Tree가 시작점을 잡지 못하고, 인덱스를 처음부터 끝까지 훑었습니다(access_type=index, 스캔당 추정 199,704행으로 표에 적힌 테이블 행수와 같음). 다만 `using_index=true`라서 필요한 컬럼(id, amount, created_at)을 인덱스만으로 읽는 커버링 인덱스예요. 기준 문서의 예외대로 테이블 풀스캔보다는 나을 수 있습니다. 확인할 것은 세 가지예요. 인덱스 정의(describeSchema)에 `created_at`이 선두인 인덱스가 있는지 보고, `EXPLAIN ANALYZE`(TREE)로 실제 반환 행수를 재서 추정(rows_produced_per_join 66,561, filtered 33.33%)과 10배 이상 벌어지는지 확인하고, 약 2개월 조건이 테이블의 몇 %를 뽑는지 선택도를 확인해야 해요. 이번 입력에는 실제 행수와 회귀 전후 수치(rows/call 변화)가 없어서, 카디널리티 오추정이나 통계 노후 여부는 지금으로선 판단할 수 없습니다.
```
정답(사람): 

---

## 7. M5 / STRUCTURE / rep 3

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
가장 유력한 원인은 **복합 인덱스의 선두 컬럼 누락**입니다. 사용 중인 인덱스 `exp_mask_orders_amount_created_idx`는 `(amount, created_at)` 순서인데, WHERE 절에는 두 번째 컬럼인 `created_at`만 있습니다. B+Tree는 선두 컬럼부터 시작점을 잡기 때문에 범위 탐색을 하지 못하고, 인덱스 전체(rows_examined_per_scan 199,704, 테이블 추정 행수와 같음)를 훑는 `access_type=index`로 떨어졌습니다.

다만 `using_index=true`라서 테이블 접근 없이 인덱스만 읽는 커버링 상태입니다. 판단 기준 문서의 예외에 따르면 이 경우 테이블 풀스캔보다는 나을 수 있으므로, 이 계획만으로 "나쁘다"고 단정하지는 않습니다.

확인할 것은 두 가지입니다.
- **실제 선택도:** filtered 33.33%는 옵티마이저의 추정일 뿐이고, 비교값도 "약 2개월 전"으로 가려져 있어 실제로 몇 행이 걸러지는지는 모릅니다. `EXPLAIN ANALYZE`(8.4는 TREE 포맷)로 추정 행수와 실제 행수를 비교해야 합니다.
- **인덱스 정의:** `created_at`이 선두인 인덱스가 따로 있는지 확인해야 합니다. 없다면 `created_at`을 선두로 둔 복합 인덱스가 판단 기준에 맞는 처방입니다. 다만 실제로 뽑히는 행이 테이블 대부분이 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 8. M6 / NONE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
규칙 기준으로는 비효율 신호가 없습니다. `access_type=ref`로 `exp_mask_orders_status_idx`를 상수 등치(`ref: const`)로 타고 있어서, 풀스캔(ALL)이나 인덱스 풀스캔(index), filesort, 임시 테이블 신호가 모두 없습니다. 다만 문서는 선택도를 최종 판단 기준으로 봅니다. 추정 `rows_examined_per_scan` 99,852행은 전체 약 199,704행의 절반쯤이어서 `status = 'PAID'`는 선택도가 낮은 조건입니다. 결과가 테이블의 큰 비중을 차지하면 인덱스로 랜덤 접근하는 비용이 순차로 전체를 읽는 비용에 가까워지거나 넘어설 수 있으므로, 이 경우 인덱스를 타는 것이 꼭 이득이라고 단정할 수 없습니다. 이 수치는 `EXPLAIN`의 추정치일 뿐이니 먼저 `EXPLAIN ANALYZE`(8.4는 TREE 포맷)로 실제 `rows`·`loops`를 보고 추정과 10배 이상 어긋나는지 확인해 주세요. 그 결과와 실제 소요 시간, 회귀 전후의 rows/call 변화가 없으면, 이 쿼리가 회귀의 원인인지와 인덱스 무력화 근본원인 5종 중 어디에 해당하는지는 현재 근거로 판단할 수 없습니다.
```
정답(사람): 

---

## 9. M6 / NONE / rep 3

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
규칙 기반 판정과 같이 **판단 기준 문서에 걸리는 비효율 신호는 없습니다**. `access_type=ref`로 `exp_mask_orders_status_idx` 인덱스를 상수(`const`)로 타고 있고, `ALL`·`index` 풀스캔이나 `using_filesort`, `using_temporary_table`도 없습니다.

다만 주의할 점이 하나 있습니다. 추정 읽기 행수(`rows_examined_per_scan`)가 99,852행으로 전체 약 199,704행의 절반쯤입니다. 문서의 "낮은 선택도" 항목에 해당합니다. 결과가 테이블의 대부분이면 인덱스로 랜덤 접근하는 것보다 순차로 전체를 읽는 편이 쌀 수 있어서, 인덱스를 탄다고 해서 효율적이라고 단정할 수 없습니다.

이 값은 `EXPLAIN` 추정치일 뿐이고 실제 행수는 없습니다. 그래서 카디널리티 오추정이 있는지, 인덱스 무력화 근본원인 5종 중 어디에 해당하는지는 이 입력만으로 판단할 수 없습니다. 회귀 전후를 비교할 자료(이전 계획, rows/call 변화)도 없어서 회귀의 원인은 모릅니다.

확인할 것은 두 가지입니다.
1. 같은 쿼리를 `EXPLAIN ANALYZE`(TREE 포맷)로 실행해 추정 99,852행과 실제 행수를 비교합니다. loops가 1보다 크면 행수에 loops를 곱해 총량으로 봅니다.
2. rows/call이 이전보다 늘었는지 봅니다. 늘 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 10. M6 / STRUCTURE / rep 3

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 규칙상 비효율 신호는 없습니다.** access_type=ref로 `exp_mask_orders_status_idx` 인덱스를 타고 있고, filesort와 임시 테이블도 없습니다. 다만 추정 행수 99,852는 전체 약 199,704행의 절반 정도입니다. 기준 문서의 선택도 원칙에 따르면 이 정도면 인덱스로 행을 하나씩 랜덤 접근하는 것보다 전체를 순차로 읽는 편이 쌀 수 있습니다. 이 쿼리의 핵심 쟁점은 풀스캔이 아니라 낮은 선택도입니다.

이 값은 옵티마이저 추정치일 뿐이고, 회귀 전후의 rows/call이나 실제 행수는 입력에 없습니다. 그래서 회귀 원인이 무엇인지는 이 자료만으로는 모릅니다.

**확인할 것:**
1. **실제 행수:** `EXPLAIN ANALYZE`(TREE 포맷)로 실제 rows를 확인하세요. 추정과 10배 이상 차이 나면 통계가 오래됐을 가능성을 봅니다.
2. **커버링 여부:** describeSchema로 인덱스 정의를 보고, 조회 컬럼 `amount`가 인덱스에 없어 행마다 테이블을 다시 읽는지 확인하세요.
3. **값별 분포:** 바인딩되는 status 값마다 행이 몇 개인지 확인하세요. 값에 따라 선택도가 크게 달라질 수 있습니다.
```
정답(사람): 

---

## 11. M7 / FULL / rep 2

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
이 실행계획에는 판단 기준에 걸리는 비효율 신호가 없습니다. `access_type`이 `ref`이고 `exp_mask_orders_status_idx` 인덱스를 `status` 등치 조건(`ref: const`)으로 타고 있으며, `ALL`, `using_filesort`, `using_temporary_table`, `access_type=index` 중 어느 것도 나오지 않습니다. 옵티마이저 추정은 2,000행으로, 약 199,704행 중 1% 정도입니다. 기준 문서의 선택도 원칙대로라면 1% 정도만 뽑을 때는 인덱스로 찾는 쪽이 압도적으로 싸므로 이 플랜 선택은 합리적입니다. 다만 이 계획은 추정치만 있는 EXPLAIN이라 실제로 읽은 행수를 알 수 없습니다. 입력에 rows/call 변화 수치도 없어서, 회귀가 있었다면 그 원인이 무엇인지는 이 자료만으로는 모릅니다. 확인할 것은 `EXPLAIN ANALYZE`(8.4는 TREE 포맷)로 estimated_rows 2,000과 actual_rows를 비교하는 일입니다. 10배 이상 차이가 나면 통계 노후나 `status` 값 분포 쏠림(특정 값의 선택도가 낮음)을 의심하고, 바인딩되는 파라미터 타입이 컬럼 타입과 같은지(암시적 형변환)도 함께 확인하세요.
```
정답(사람): 

---

## 12. M7 / FULL / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
이 실행계획에는 판단 기준 문서의 MySQL 비효율 신호가 없습니다. access_type이 `ref`이고 `exp_mask_orders_status_idx` 인덱스를 `status` 등치 조건(`const`)으로 타고 있습니다. `ALL`이나 `index` 풀스캔, `using_filesort`, `using_temporary_table`도 보이지 않습니다. 추정 읽기 행수는 2,000행으로 테이블 약 199,704행의 1% 정도입니다. 기준 문서의 선택도 기준으로 보면 인덱스로 일부만 찾는 편이 유리한 구간이라 지금 플랜은 합리적입니다. 다만 이 결과는 EXPLAIN 추정값(`rows_examined_per_scan`)만 담고 있습니다. 그래서 실제 행수와 비교하는 카디널리티 오추정이나 통계 노후 여부는 여기서 판단할 수 없습니다. 입력에 rows/call 변화 같은 회귀 수치도 없어서 회귀가 있다면 그 원인도 알 수 없습니다. 확인하려면 `EXPLAIN ANALYZE`(8.4는 TREE 포맷)로 실제 `rows`와 `loops`를 받아 추정 2,000행과 10배 이상 차이 나는지 보고, 회귀 감지에서 rows/call이 늘었는지 함께 보면 됩니다. 특정 `status` 값에 행이 몰려 있으면 선택도가 달라질 수 있으니, 그런 값이 들어오는 경우의 실측도 필요합니다.
```
정답(사람): 

---

## 13. M7 / NONE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 이 실행계획에는 판단 기준 문서에 걸리는 비효율 신호가 없고, 정상으로 보입니다.** access_type이 `ALL`이나 `index`가 아니라 `ref`이고, `exp_mask_orders_status_idx`를 `status = const` 조건으로 타고 있습니다. `using_filesort`와 `using_temporary_table`도 없습니다. 추정 검사 행수는 2,000행으로 전체 약 199,704행의 약 1%입니다. 문서의 선택도 기준에서 "1%만 뽑는다면 인덱스로 찾는 게 압도적으로 싸다"는 경우에 해당하므로, 인덱스를 고른 것은 합리적입니다.

다만 이 계획은 `EXPLAIN FORMAT=JSON`의 **추정치**만 담고 있어서 실제 행수는 알 수 없습니다. 그래서 문서의 핵심 신호인 카디널리티 오추정(추정 대비 실제 10배 이상 괴리)이 있는지는 이 입력만으로 판단할 수 없습니다. 확인하려면 `EXPLAIN ANALYZE`를 TREE 포맷으로 돌려 estimated_rows와 actual_rows를 비교해야 합니다. MySQL 8.4는 `EXPLAIN ANALYZE`에 JSON 포맷을 거부합니다. 실제 CANCELLED 비율이 추정보다 훨씬 크게 나오면, 통계 노후나 낮은 선택도를 원인 후보로 보면 됩니다.
```
정답(사람): 

---

## 14. M7 / NONE / rep 2

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 판단 기준 문서에 걸리는 비효율 신호는 없습니다.**

1. `access_type=ref`로 `exp_mask_orders_status_idx` 인덱스를 `status` 상수 조건으로 타고 있습니다. `ALL`, `index`, `using_filesort`, `using_temporary_table` 같은 MySQL 비효율 신호는 보이지 않으므로 규칙 기반 지적과 판정이 같습니다.
2. 선택도로 봐도 옵티마이저 추정 2,000행은 전체 약 199,704행의 약 1%입니다. 문서 기준으로 이 정도 비율이면 인덱스로 찾는 쪽이 유리하므로 지금 접근 경로는 합리적입니다.
3. 다만 이 계획은 추정치만 담긴 `EXPLAIN`이라 실제 행수가 없습니다. 추정과 실제가 10배 이상 벌어지는지, 곧 카디널리티 오추정과 5가지 근본원인(암시적 형변환, 통계 노후 등)에 해당하는지는 **모릅니다**. 입력에 rows/call 변화 같은 회귀 수치도 없어서 회귀의 원인이라고 할 근거도 없습니다.
4. 확인할 것은 두 가지입니다. 하나는 `EXPLAIN ANALYZE`(TREE 포맷)로 이 노드의 `actual rows`가 추정 2,000과 비슷한지 보는 것입니다. 다른 하나는 회귀 감지 쪽에서 이 쿼리의 rows examined/call이 실제로 늘었는지 보는 것입니다. 실제 행수가 추정보다 훨씬 크다면 `CANCELLED` 비율이 바뀐 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 15. M7 / STRUCTURE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
1. 이 실행계획에는 판단 기준 문서의 비효율 신호가 없습니다. `access_type`은 `ALL`이나 `index`가 아닌 `ref`이고, `exp_mask_orders_status_idx`를 `status` 등치(`const`) 조건으로 타며, filesort와 임시 테이블도 없습니다.
2. 선택도도 인덱스에 유리합니다. 추정 검사 행수 2,000행은 약 199,704행 중 약 1%입니다. 문서의 "1%만 뽑는다면 인덱스가 압도적으로 싸다"에 해당하므로 규칙 기반 판정("신호 없음")과 일치합니다.
3. 다만 이 계획은 `EXPLAIN FORMAT=JSON`의 추정치만 담고 있습니다. 실제 행수가 없어서 카디널리티 오추정(추정 대비 10배 이상 괴리)이나 통계 노후 여부는 이 입력만으로는 판단할 수 없습니다. 회귀 비교 데이터(rows/call 변화 등)도 주어지지 않아, 회귀가 있었는지와 그 원인도 알 수 없습니다.
4. 확인할 것은 세 가지입니다.
   - MySQL 8.4의 `EXPLAIN ANALYZE`(TREE 포맷)로 이 노드의 `actual_rows`를 추정치 2,000과 비교합니다.
   - `status` 값 분포가 치우쳐 있는지 봅니다. 특정 값은 선택도가 낮아서, 그 값에서는 옵티마이저가 풀스캔을 고르는 게 정상일 수 있습니다.
   - 바인딩되는 `?` 파라미터의 타입이 컬럼 타입과 같은지 봅니다. `key …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 16. M8 / FULL / rep 2

판정: qwen3:14b: missed | llama3.1:8b: candidate | qwen3:8b: missed

응답:

```
**판정: 이 실행계획만 보면 비효율이라고 단정할 근거가 부족합니다. 규칙의 "인덱스 풀스캔" 지적은 문서에 적힌 예외(커버링 인덱스)에 해당할 가능성이 큽니다.** 테이블 접근은 `access_type=index`지만, `key=PRIMARY`로 `used_columns`가 `id` 하나뿐이고 `using_index=true`입니다. 테이블을 읽지 않고 인덱스만으로 끝나는 커버링 인덱스라서, 판단 기준 문서의 "커버링 인덱스라 테이블 접근이 없으면 풀스캔보다 나을 수 있다"는 예외에 맞습니다. `using_filesort=false`이므로 ORDER BY도 PK 인덱스 순서로 해결돼 별도 정렬은 없고, WHERE 절이 없어서 인덱스 무력화 근본원인 5종(암시적 형변환, 함수 적용, 선두 누락 등) 중 어느 것에도 해당하지 않습니다. 추정 스캔 행수(`rows_examined_per_scan` 190,020)가 테이블 행수(약 199,704)와 거의 같은 건 확인되지만, 이것은 추정값입니다. 실제 행수가 없어서 카디널리티 오추정이나 rows/call 폭증이 있는지는 이 입력만으로 모릅니다. LIMIT/OFFSET 값에 따라 실제로 읽는 행 수가 어떻게 달라지는지도 판단 기준 문서에 규칙이 없어 판정할 수 없습니다. 따라서 `EXPLAIN ANALYZE`(TREE 포맷)로 실제 `rows`와 `loops`를 확인하고, 회귀 구간에서 넘 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 17. M8 / NONE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
규칙이 지적한 `access_type=index`는 PRIMARY 인덱스를 처음부터 훑는 인덱스 풀스캔이 맞습니다. 다만 `using_index=true`이고 `using_filesort=false`라서 판단 기준 문서의 예외, 즉 테이블에 접근하지 않는 커버링 인덱스 경우에 해당합니다. 그러니 이 풀스캔 자체를 나쁘다고 단정하기는 어렵습니다. 대신 `rows_examined_per_scan`이 190,020(OFFSET 190,000 + LIMIT 20)으로 전체 약 199,704행과 거의 같습니다. 20행을 돌려주려고 인덱스 대부분을 읽고 있다는 뜻이고, 가장 유력한 원인은 WHERE 조건이 아니라 큰 OFFSET으로 보입니다. 그런데 판단 기준 문서에는 OFFSET 페이지네이션 규칙이 없고, 근본원인 5종(암시적 형변환·함수 적용·통계 노후·낮은 선택도·선두 누락/앞 와일드카드)에도 WHERE 절이 없어 맞는 게 없습니다. 그래서 원인을 확정하거나 해법을 제시하는 건 이 기준 밖이라 모릅니다. 확인할 것은 두 가지입니다. 이 계획은 추정치뿐인 EXPLAIN이니 `EXPLAIN ANALYZE`(TREE)로 실제 행수를 확인하고, 회귀라면 OFFSET 값이 커지면서 rows/call이 함께 늘었는지 봐야 합니다.
```
정답(사람): 

---

## 18. M8 / NONE / rep 2

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
규칙이 지적한 `access_type=index`는 PRIMARY 인덱스 풀스캔이지만, `using_index=true`로 커버링 인덱스이고 `using_filesort=false`라 ORDER BY도 인덱스 순서로 해결됩니다. 판단 기준 문서의 예외("커버링 인덱스라 테이블 접근이 없으면 풀스캔보다 나을 수 있다")에 해당하므로, 접근 경로 자체를 비효율의 원인으로 보기는 어렵습니다. 비용이 큰 이유는 OFFSET 190000입니다. 20행을 반환하려고 `rows_examined_per_scan`=190,020행, 즉 테이블 약 199,704행의 대부분을 인덱스 순서대로 훑습니다. 다만 판단 기준 문서에는 OFFSET 페이지네이션 규칙이 없어서 이 패턴을 원인으로 확정하거나 처방을 내리는 근거는 없습니다. 인덱스 무력화 근본원인 5종에도 해당하는 신호가 없습니다. 확인할 것은 두 가지입니다. 먼저 `EXPLAIN ANALYZE`(TREE)로 실제 rows를 봐서 추정 190,020과 크게 다른지 확인하고, 회귀 감지의 rows/call이 이전보다 늘었는지 봐서 OFFSET 값이나 테이블 크기가 커진 데 따른 변화인지 가려야 합니다. 지금 입력에는 이전 수치가 없어서 회귀 여부는 판단할 수 없습니다.
```
정답(사람): 

---

## 19. M8 / NONE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
규칙 기반 지적인 인덱스 풀스캔(access_type=index)은 맞지만, 이 계획은 PRIMARY 인덱스만 읽고(`using_index: true`) ORDER BY도 인덱스 순서로 처리돼(`using_filesort: false`) 테이블 접근과 정렬이 없습니다. 판단 기준 문서가 access_type=index의 예외로 드는 "커버링 인덱스라 풀스캔보다 나을 수 있는" 경우에 해당합니다. 이 쿼리에는 WHERE 조건이 없어서 규칙 메시지가 권하는 "범위 조건 검토"는 들어맞지 않습니다. 예상 읽기 행수(`rows_examined_per_scan` 190,020)가 테이블 약 199,704행의 대부분인 것은 OFFSET 190000을 건너뛰느라 앞쪽 행을 전부 읽기 때문으로 보입니다. 다만 문서에는 큰 OFFSET 페이지네이션을 판정하는 규칙이 없어서, 이것이 문제인지와 어떻게 고칠지는 기준만으로 판정할 수 없습니다(모름). 확인할 것은 두 가지입니다. `EXPLAIN ANALYZE`(TREE 포맷)로 이 190,020이 실제로 읽은 행수와 맞는지 보고, 회귀라면 OFFSET 값이나 rows/call이 이전보다 커졌는지 대조해야 합니다. 인덱스 무력화 근본원인 5종에 해당한다는 근거는 이 계획에 보이지 않습니다.
```
정답(사람): 

---

## 20. M8 / STRUCTURE / rep 1

판정: qwen3:14b: candidate | llama3.1:8b: candidate | qwen3:8b: missed

응답:

```
가장 유력한 판단은 **정상에 가까운 인덱스 풀스캔**입니다. `access_type=index`이지만 `key=PRIMARY`, `using_index=true`라서 커버링 인덱스로 테이블에 접근하지 않고, 기준 문서의 예외("커버링 인덱스라 테이블 접근이 없으면 풀스캔보다 나을 수 있다")에 해당합니다. `using_filesort=false`이므로 `ORDER BY id`는 PK 순서로 해결되어 별도 정렬도 없습니다. WHERE 조건이 없어 선택도가 사실상 100%이므로, 규칙 기반 지적의 "범위 조건 검토"는 이 쿼리에 맞지 않습니다. 추정 행수 190,020은 LIMIT/OFFSET을 반영하지 않은 값입니다. 실제로 읽는 행수와 `OFFSET` 값이 커질 때의 비용은 판단 기준 문서에 규칙이 없어 이 계획만으로는 모릅니다. 확인하려면 실제 바인딩 값으로 `EXPLAIN ANALYZE`(TREE 포맷)을 실행해 actual rows와 loops를 보고, rows/call이 OFFSET에 비례해 늘어나는지 회귀 추이와 함께 봐야 합니다.
```
정답(사람): 

---

## 21. P3 / FULL / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
Seq Scan이 잡혔지만, 판단 기준대로라면 지금은 정상 선택일 가능성이 가장 큽니다. 전체 약 200,000행 중 플래너의 추정 행수(Plan Rows)가 199,978행입니다. 즉 조건 `amount > ?`가 사실상 테이블 전체를 가져온다고 본 것이고, 이런 낮은 선택도에서는 인덱스로 랜덤 접근하는 것보다 순차로 전부 읽는 편이 싸서 플래너가 일부러 Seq Scan을 고릅니다. 그래서 규칙 기반 지적처럼 바로 인덱스를 추가하는 것은 이 근거만으로는 권하기 어렵습니다. 다만 이 계획은 추정만 있는 EXPLAIN이고 바인드 값이 `?`로 들어가 있어 실제 행수(Actual Rows)가 없습니다. 그래서 추정과 실제가 10배 이상 갈라지는지(카디널리티 오추정, 통계 노후 등)는 판정할 수 없습니다. 회귀 전후 계획이나 rows/call 변화도 주어지지 않아 무엇이 회귀를 일으켰는지는 모릅니다. 실제로 쓰는 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`를 돌려 Actual Rows와 Rows Removed by Filter를 확인해 주세요. 실제로는 소수만 걸러진다면 그때 `amount` 인덱스 부재나 통계 노후를 의심하면 됩니다.
```
정답(사람): 

---

## 22. P3 / FULL / rep 2

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 인덱스가 없어서라고 단정할 수 없고, 플래너가 일부러 고른 정상 Seq Scan일 가능성이 더 큽니다.** 추정 행수(Plan Rows)가 199,978행으로, 테이블 약 200,000행과 거의 같습니다. 플래너는 `amount > ?` 조건이 테이블 대부분을 돌려준다고 보고 있습니다. 기준 문서의 "낮은 선택도" 규칙대로라면 이 경우 인덱스로 랜덤 접근하는 것보다 순차로 전부 읽는 편이 싸서 Seq Scan을 고르는 게 맞습니다. 다만 이 플랜은 ANALYZE 없는 추정 전용 플랜이라 실제 행수(Actual Rows)가 없습니다. 게다가 파라미터가 `?`인 일반 플랜이라서, 추정이 맞는지(카디널리티 괴리가 있는지)와 5가지 근본원인 중 어디에 해당하는지는 지금 입력만으로는 모릅니다. rows/call 변화 같은 회귀 수치도 주어지지 않아서 회귀 원인도 판단할 수 없습니다. 확인할 것: 실제로 자주 쓰는 `amount` 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 떠서 Plan Rows와 Actual Rows, Rows Removed by Filter를 비교하세요. 실제 결과가 소수(10배 넘는 괴리)라면 그때 통계 노후(마지막 ANALYZE 시각)와 `amount` 인덱스 여부를 점검하면 됩니다.
```
정답(사람): 

---

## 23. P3 / FULL / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
가장 유력한 판단은 "정상일 가능성이 높은 Seq Scan"입니다. 플래너 추정 행수(Plan Rows 199,978)가 테이블 전체(약 200,000행)와 거의 같습니다. 선택도 기준으로 결과가 테이블의 대부분이면 인덱스로 랜덤 접근하기보다 순차로 읽는 편이 싸서, 플래너가 일부러 풀스캔을 고릅니다. 다만 주어진 계획은 추정치만 있는 EXPLAIN이고 Actual Rows가 없습니다. 그래서 추정과 실제 사이에 10배 이상 괴리가 있는지(통계 노후 등 근본원인이 있는지)는 지금 근거로는 판정할 수 없습니다. 회귀 전후 rows/call 변화도 주어지지 않아 "왜 느려졌는가"도 모릅니다. 확인할 것은 세 가지입니다. 실제 파라미터 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Plan Rows와 Actual Rows를 비교하고, `amount` 컬럼에 인덱스가 있는지 확인하고, 실제로 뽑히는 행이 소수라면 그때 인덱스 부재나 통계 노후를 의심합니다.
```
정답(사람): 

---

## 24. P3 / NONE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 인덱스 부재가 원인일 가능성은 낮고, 이 Seq Scan은 정상 선택일 가능성이 높습니다.** 플래너는 `amount > 10` 조건에 199,978행이 맞는다고 추정했고, 이는 테이블 약 200,000행의 거의 전부입니다. 판단 기준 문서의 선택도 원칙에 따르면 결과가 테이블 대부분일 때는 전체를 순차로 읽는 편이 인덱스로 랜덤 접근하는 것보다 쌉니다. 그래서 플래너가 일부러 Seq Scan을 고를 수 있고, 이 경우 규칙 기반 지적("인덱스를 검토하세요")은 오탐일 수 있습니다.

다만 이 계획은 ANALYZE 없는 추정치(Plan Rows)만 담고 있습니다. 실제 행수(Actual Rows)가 없어서 추정이 맞았는지, 곧 카디널리티 오추정이나 통계 노후가 있는지는 지금 입력으로는 알 수 없습니다.

**확인할 것:** 타임아웃을 건 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 Plan Rows와 Actual Rows를 비교하세요. 실제 행수가 추정보다 크게(10배 이상) 적으면, 통계 수집 시각을 확인해 통계 노후를 의심해야 합니다.
```
정답(사람): 

---

## 25. P3 / STRUCTURE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 규칙 기반 지적은 오탐일 가능성이 높습니다.** 추정 결과 행수(Plan Rows 199,978)가 테이블 행수(약 200,000행)와 거의 같아서, 조건 `amount > ?`가 테이블 대부분을 뽑는 것으로 추정됩니다. 판단 기준 문서의 선택도 원칙에 따르면, 결과가 테이블 대부분일 때는 인덱스로 랜덤 접근하는 것보다 순차로 읽는 편이 싸기 때문에 플래너가 일부러 Seq Scan을 고릅니다. 이 경우 인덱스를 추가해도 효과를 기대하기 어렵습니다.

다만 이 계획은 추정치만 있는 EXPLAIN이라 실제 행수가 없습니다. 그래서 카디널리티 오추정(추정과 실제의 10배 이상 괴리)이나 통계 노후 여부는 이 입력만으로는 판단할 수 없습니다. 비교값도 가려져 있어("2자리") 조건이 실제로 얼마나 걸러내는지도 알 수 없습니다.

**확인할 것:**
- 실제 파라미터로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Actual Rows, Rows Removed by Filter와 Plan Rows를 비교합니다.
- 실제 결과가 소수인데 추정만 크게 빗나간 것이라면 통계 노후를 의심하고 통계 수집 시각을 확인합니다.
- 그다음 `amount` 컬럼에 인덱스가 있는지 확인합니다.
```
정답(사람): 

---

## 26. P3 / STRUCTURE / rep 2

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 인덱스 부재보다는 저선택도로 인한 정상 풀스캔일 가능성이 높습니다.** 플래너는 약 200,000행 중 199,978행(Plan Rows)이 조건을 만족한다고 추정하고 Seq Scan을 골랐습니다. 판단 기준 문서에 따르면 결과가 테이블의 대부분일 때는 인덱스로 랜덤 접근하는 것보다 순차로 읽는 편이 싸기 때문에, 플래너가 일부러 풀스캔을 고릅니다. 그래서 규칙 기반 지적("WHERE 조건에 맞는 인덱스를 검토하세요")은 오탐일 수 있습니다.

다만 이 판정은 추정 행수만 보고 내린 것입니다. 이 계획에는 실제 행수(Actual Rows)가 없어서, 문서가 말하는 근본 원인인 카디널리티 오추정이나 통계 노후를 가려낼 근거가 없습니다. 비교값도 가려져 있어 실제 선택도는 판단할 수 없습니다.

**확인할 것:**
1. 같은 파라미터로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Plan Rows와 Actual Rows가 10배 이상 벌어지는지 봅니다. SELECT 전용으로, `SET LOCAL statement_timeout`을 걸고 실행합니다.
2. 회귀 감지의 rows/call이 실제로 폭증했는지 봅니다. 이 입력에는 그 수치가 없어 회귀 여부는 모릅니다.
3. `amount` 컬럼에 인덱스가 있는지 describeSchema로 확인합니다.
```
정답(사람): 

---

## 27. P3 / STRUCTURE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 인덱스 부재보다는 선택도가 낮아서 플래너가 일부러 고른 정상적인 Seq Scan일 가능성이 큽니다.** 테이블이 약 200,000행인데 Seq Scan 노드의 추정 행수(Plan Rows)가 199,978행입니다. 옵티마이저는 `amount > ?` 조건에 테이블 거의 전부가 걸린다고 보고 있습니다. 판단 기준의 선택도 절과 PostgreSQL Seq Scan 예외에 따르면, 결과가 테이블 대부분이면 인덱스로 랜덤 접근하는 것보다 순차로 읽는 편이 싸서 플래너가 풀스캔을 일부러 고릅니다. 그래서 여기에 `amount` 인덱스를 추가해도 효과가 없을 수 있습니다.

다만 이 계획은 추정만 담긴 EXPLAIN이라 Actual Rows가 없습니다. 추정과 실제가 10배 이상 벌어지는지는 판단할 수 없고, 통계 노후 여부도 알 수 없습니다. 바인드 값도 가려져 있어서(2자리) 실제로 몇 %가 걸리는지 모릅니다.

**확인할 것:**
- 실제 파라미터로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Actual Rows와 Rows Removed by Filter를 봅니다.
  - 실제 반환 행이 추정처럼 거의 전부라면 정상입니다.
  - 실제 반환 행이 추정보다 크게 적다면 카디널리티 오추정입니다. 이 경우 `amount` 통계 수집 시각(통계 노후)과 `amount` 인덱스 유무를 확인합니다. …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 28. P4 / FULL / rep 2

판정: qwen3:14b: candidate | llama3.1:8b: confirmed | qwen3:8b: confirmed

응답:

```
**판정: 이 실행계획에서는 판단 기준 문서의 비효율 신호가 보이지 않습니다. 회귀 원인도 이 입력만으로는 알 수 없습니다.** 플랜은 `exp_mask_orders_amount_created_idx`를 쓰는 Bitmap Index Scan과 Bitmap Heap Scan입니다. 기준 문서의 PostgreSQL 신호인 Seq Scan, Nested Loop 안쪽 Seq Scan, Sort Method: external은 하나도 없습니다. `amount`가 복합 인덱스의 선두 컬럼으로 보여서, 인덱스 무력화 근본원인 5종 중 "복합 인덱스 선두 누락"에도 해당하지 않습니다. 추정 행수는 2,037행으로 약 200,000행의 1% 정도입니다. 기준 문서는 이런 고선택도 조건이면 인덱스 사용이 맞다고 봅니다. 다만 이 플랜은 `ANALYZE` 없이 뽑은 추정치라 Actual Rows가 없고, 조건값도 `?`로 가려져 있습니다. 그래서 카디널리티 오추정이나 통계 노후 여부는 판단할 근거가 없습니다. 확인할 것은 실제 파라미터 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Plan Rows와 Actual Rows가 10배 이상 벌어지는지 보는 것, 그리고 회귀 감지 쪽에서 rows/call이 실제로 늘었는지 보는 것입니다. 특정 값에서 조건에 맞는 행이 테이블 대부분이 되면 기준 문서대로 플래너가  …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 29. P4 / FULL / rep 3

판정: qwen3:14b: candidate | llama3.1:8b: confirmed | qwen3:8b: confirmed

응답:

```
**판정: 문서 기준으로는 비효율 신호가 없습니다.** 실행계획은 `exp_mask_orders_amount_created_idx`를 쓰는 Bitmap Index Scan에서 Bitmap Heap Scan으로 이어지고, 문서의 PostgreSQL 규칙 3가지(Seq Scan, Nested Loop 안쪽 Seq Scan, Sort Method: external) 중 어느 것에도 해당하지 않습니다. 추정 행수는 2,037행으로 약 200,000행의 1% 안팎입니다. 문서의 선택도 기준대로라면 이 정도는 인덱스로 찾는 편이 유리하니, 인덱스 경로를 고른 것은 합리적으로 보입니다.

**모르는 것: 실제 행수가 없어 카디널리티 오추정(추정과 실제가 10배 이상 차이)은 판단할 수 없습니다.** 이 계획은 추정치만 있는 EXPLAIN이라 실제 행수(Actual Rows)가 없습니다. 게다가 `amount > ?`가 바인드 변수라서, 들어오는 값에 따라 실제 선택도가 크게 달라질 수 있습니다.

**확인할 것:** 대표 파라미터 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 다음을 보세요.
- 추정 행수(Plan Rows)와 실제 행수(Actual Rows)의 차이
- Rows Removed by Index Recheck
- Shared Hit/Read Blocks

만약 결과가 테이블 대부분을 차지 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 30. P5 / FULL / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
현재로선 인덱스가 없어서 생긴 문제라기보다, 옵티마이저가 일부러 Seq Scan을 골랐을 가능성이 가장 큽니다. 추정 행수(Plan Rows 199,980)가 테이블 행수(약 200,000)와 거의 같아서 조건에 맞는 행이 테이블의 대부분이라고 보고 있기 때문이고, 판단 기준의 "낮은 선택도" 예외처럼 이럴 때는 인덱스로 랜덤 접근하는 것보다 순차로 읽는 편이 쌉니다. 다만 Filter에 실제 날짜가 아니라 `'?'::date`가 들어 있어서 이 추정이 실제 파라미터 값을 반영한 것인지 알 수 없습니다. 실측 행수(Actual Rows)도 없어서 카디널리티 오추정(통계 노후 등)인지는 이 입력만으로는 판정할 수 없습니다. 확인할 것은 세 가지입니다. 실제 날짜 값을 넣고 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 돌려 Plan Rows와 Actual Rows가 10배 이상 벌어지는지 봅니다. `created_at`에 인덱스가 있는지 스키마에서 확인합니다. 벌어진다면 통계 수집 시각이 오래됐는지 확인합니다. 실제로 뽑히는 행이 적은데도 Seq Scan이라면 그때 `created_at` 인덱스를 추가하는 것을 검토하면 됩니다.
```
정답(사람): 

---

## 31. P5 / FULL / rep 2

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
실행계획의 추정 행수(Plan Rows 199,980)가 테이블 전체(약 200,000행)와 거의 같습니다. 그래서 가장 유력한 해석은 **낮은 선택도 때문에 플래너가 일부러 Seq Scan을 골랐다**는 것입니다. 판단 기준 문서에 따르면 결과가 테이블 대부분일 때는 인덱스로 랜덤 접근하는 것보다 순차로 읽는 쪽이 싸므로, 이 경우 Seq Scan은 정상입니다. 다만 비교값이 가려진 `'?'::date`로 들어가 있어, 이 추정이 실제 파라미터 값의 선택도를 반영하는지는 이 계획만으로는 알 수 없습니다. 또 ANALYZE 없이 뽑은 계획이라 Actual Rows가 없어서, 추정과 실제의 괴리나 5종 근본원인에 해당하는지도 판정할 근거가 없습니다. 실제 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행해 Plan Rows와 Actual Rows, Rows Removed by Filter를 비교해 보세요. 함께 `created_at`에 인덱스가 있는지(describeSchema)도 확인하세요. 실제로 걸러지는 행이 적은데도 Seq Scan이 나올 때에만 인덱스 부재나 통계 노후를 의심하면 됩니다.
```
정답(사람): 

---

## 32. P5 / NONE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 인덱스가 없어서 생긴 문제로 보기 어렵습니다. 조건이 테이블 대부분을 가져오기 때문에 플래너가 일부러 Seq Scan을 골랐을 가능성이 가장 큽니다.** 플래너가 추정한 결과 행수(Plan Rows)는 199,980행이고 테이블은 약 200,000행입니다. 판단 기준 문서의 "선택도" 절과 PostgreSQL Seq Scan 예외에 따르면, 이렇게 결과가 테이블의 거의 전부일 때는 인덱스로 무작위로 읽는 것보다 순서대로 전체를 읽는 편이 쌉니다. 따라서 "인덱스를 검토하라"는 규칙 기반 지적은 오탐일 가능성이 높습니다. Filter도 `'2000-01-01'::date`로 상수 쪽만 형이 바뀌어 있어서, 컬럼에 함수를 씌우거나 컬럼이 암시적으로 형변환된 패턴은 이 계획에서 보이지 않습니다. 확인할 것은 두 가지입니다. 첫째, 이 계획은 ANALYZE 없는 추정 계획이라 실제 행수가 없으니 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 Plan Rows와 Actual Rows가 10배 이상 차이 나는지 보세요. 둘째, `created_at >= '2000-01-01'`이 거의 모든 행을 가져오는 게 의도한 조건인지 업무 쪽에 확인하세요.
```
정답(사람): 

---

## 33. P5 / STRUCTURE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
가장 유력한 판단은 선택도가 낮아서 플래너가 일부러 고른 정상적인 Seq Scan이라는 것입니다. 약 200,000행 테이블에서 추정 결과가 199,980행(Plan Rows)이고, 판단 기준은 결과가 테이블 대부분이면 인덱스로 랜덤 접근하는 것보다 순차로 읽는 게 싸다고 봅니다. 그래서 규칙 기반 지적처럼 "인덱스를 검토하라"고 바로 처방하기는 어렵습니다. 조건값이 약 27년 전 날짜라 거의 모든 행이 걸리는 것도 이 판단과 맞습니다.

다만 입력이 추정치만 있는 EXPLAIN이라 실제 행수(Actual Rows)가 없습니다. 그래서 카디널리티 오추정이나 통계 노후 여부는 이 자료로는 알 수 없고, 회귀의 근거가 될 rows/call 변화 수치도 주어지지 않아 회귀 원인은 모릅니다. 확인할 것은 두 가지입니다. 먼저 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 Plan Rows와 Actual Rows의 차이를 보세요. 그리고 실제 운영에서 바인딩되는 `created_at` 값이 최근 날짜처럼 소수 행만 뽑는 값인지 보세요. 그런 값이라면 선택도가 높아져 `created_at` 인덱스가 효과를 낼 수 있습니다.
```
정답(사람): 

---

## 34. P5 / STRUCTURE / rep 2

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**판정: 규칙이 지적한 Seq Scan은 오탐일 가능성이 높습니다.** 추정 행수(Plan Rows)가 199,980으로 테이블 전체(약 200,000행)와 거의 같습니다. 조건 `created_at >= '약 27년 전'`이 사실상 모든 행에 맞는다는 뜻이고, 이런 낮은 선택도에서는 판단 기준대로 플래너가 인덱스 대신 순차 스캔을 일부러 고르는 것이 정상입니다. 다만 이 실행계획은 EXPLAIN 추정치만 담고 있어 Actual Rows와 비교할 수 없습니다. 그래서 카디널리티 오추정이나 통계 노후 여부는 판단 근거가 없어 알 수 없습니다. 첫째로 실제 운영 쿼리에 바인딩되는 `?` 값이 이 실행계획처럼 먼 과거인지, 최근 날짜라서 소수 행만 뽑는지 확인하세요. 최근 날짜라면 둘째로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`로 Plan Rows와 Actual Rows를 비교하고, `created_at`에 인덱스가 있는지 스키마에서 대조해야 합니다. 회귀로 판단하려면 rows/call 변화 수치도 필요한데, 이번 입력에는 없어서 회귀 여부도 판단할 수 없습니다.
```
정답(사람): 

---

## 35. P5 / STRUCTURE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
**Seq Scan은 이 경우 정상 선택으로 보입니다. 인덱스가 없어서 생긴 문제로 단정하기 어렵습니다.** 조건 `created_at >= (약 27년 전)`에 대한 추정 행수(Plan Rows)가 199,980으로, 테이블 약 200,000행의 거의 전부입니다. 판단 기준 문서의 "낮은 선택도" 예외처럼, 결과가 테이블 대부분이면 인덱스로 랜덤 접근하는 것보다 순차로 읽는 게 싸서 플래너가 일부러 풀스캔을 고릅니다. 따라서 규칙 기반 지적("WHERE 조건에 맞는 인덱스를 검토")을 그대로 따라 `created_at`에 인덱스를 만들어도 이 조건값에서는 효과가 없을 가능성이 큽니다.

다만 받은 계획은 ANALYZE 없는 추정 계획이라 실제 행수(Actual Rows)가 없습니다. 그래서 카디널리티 오추정이나 통계 노후 여부는 판단할 수 없습니다. 회귀의 원인도 이 입력만으로는 모릅니다. 확인할 것은 두 가지입니다.
1. `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 Plan Rows와 Actual Rows를 비교해, 10배 이상 차이가 나는지 봅니다.
2. 운영에서 실제로 바인딩되는 `created_at` 값이 최근 날짜라서 원래는 선택도가 높은 쿼리인지 봅니다. 그렇다면 인덱스가 의미 있어지고, 회귀 감지의 rows/call 변화와 함께 봐야 합니다.
```
정답(사람): 

---

## 36. P6 / FULL / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
Seq Scan이 테이블 약 200,000행 전체를 읽고 있고, 이 정도 크기라면 판단 기준의 "작은 테이블이라 정상"이라는 예외에는 들지 않습니다. 옵티마이저가 추정한 결과는 6,343행(Plan Rows)으로 전체의 약 3%입니다. 선택도 기준으로 보면 인덱스로 필요한 행만 찾는 편이 유리한 조건인데 Seq Scan이 선택됐으므로, 가장 유력한 원인은 `created_at`에 조건을 받을 인덱스가 없는 것입니다(원인 5종 중 "복합 인덱스 선두 누락" 가능성도 포함). 다만 인덱스 정의를 받지 못해서 이 부분은 확정할 수 없습니다. 이번 계획은 ANALYZE 없이 뽑은 추정 계획이라 Actual Rows가 없고, 그래서 추정과 실제가 어긋났는지(통계 노후 여부)도 이 입력만으로는 알 수 없습니다. 우선 describeSchema로 `created_at`이 인덱스의 선두 컬럼인지 확인하세요. 인덱스가 이미 있는데도 Seq Scan이 나온다면 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`에 `SET LOCAL statement_timeout`을 걸고 실행해 Plan Rows와 Actual Rows를 비교하고, 통계를 마지막으로 수집한 시각도 함께 확인하세요.
```
정답(사람): 

---

## 37. P6 / NONE / rep 1

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: candidate

응답:

```
**판정: Seq Scan이 걸렸고 추정 선택도가 낮아 인덱스를 검토할 만하지만, 인덱스가 없는지, 추정이 틀렸는지는 이 계획만으로 알 수 없다.**

- **추정 선택도가 낮다.** 테이블은 약 200,000행이고 플래너 추정(Plan Rows)은 6,343행이라 약 3%다. 판단 기준상 결과가 테이블 대부분일 때 일부러 고르는 "정상 풀스캔"으로 보기는 어렵다. 소량만 뽑는 쿼리는 인덱스가 압도적으로 싸므로 규칙의 지적은 타당해 보인다.
- **컬럼 쪽에 함수가 씌워지지 않았다.** Filter를 보면 형변환 `::date`는 상수 쪽에 붙어 있다. 그래서 5종 원인 중 "컬럼에 함수/표현식"에는 해당하지 않는다. 가장 유력한 원인은 created_at에 인덱스가 없거나, created_at이 복합 인덱스의 선두 컬럼이 아닌 경우다.
- **먼저 인덱스 정의부터 확인한다.** describeSchema로 exp_mask_orders의 인덱스 정의를 WHERE 컬럼 created_at과 대조한다.
- **추정이 맞는지 실측한다.** 이 계획은 ANALYZE 없는 추정 계획이라 Actual Rows가 없다. 따라서 추정과 실제의 괴리나 통계 노후는 판정할 수 없다. `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 statement_timeout을 걸고 돌려 Plan Rows와 Actual Rows를 비교한 …(전문은 ai-masking-levels.jsonl)
```
정답(사람): 

---

## 38. P6 / STRUCTURE / rep 3

판정: qwen3:14b: confirmed | llama3.1:8b: candidate | qwen3:8b: confirmed

응답:

```
200,000행 테이블에서 Seq Scan이 발생했습니다. 추정 결과는 6,343행으로 전체의 약 3%에 불과합니다. 판단 기준 문서에 따르면 이 정도로 선택도가 높은 조건은 인덱스로 찾는 쪽이 훨씬 쌉니다. 그런데 플래너가 풀스캔을 골랐으므로, 가장 유력한 원인은 `created_at`을 선두로 받는 인덱스가 없거나 선두 컬럼이 빠진 복합 인덱스뿐인 경우입니다. Filter에서 `::date` 캐스팅은 상수 쪽에 붙어 있고 컬럼에 함수가 씌워진 형태는 아닙니다. 다만 컬럼 타입을 모르므로 이 계획만으로 형변환 문제를 확정하거나 배제할 수는 없습니다. 이 계획은 추정치만 담은 EXPLAIN이라 Actual Rows가 없고, 그래서 카디널리티 오추정이나 통계 노후는 모릅니다. 확인할 것은 세 가지입니다. describeSchema로 `created_at`의 타입과 인덱스 정의를 대조하고, `EXPLAIN (ANALYZE, BUFFERS)`로 Plan Rows와 Actual Rows의 괴리를 보고, 통계 수집 시각을 확인하는 것입니다.
```
정답(사람): 

---
