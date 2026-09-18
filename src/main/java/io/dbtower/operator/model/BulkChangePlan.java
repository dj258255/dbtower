package io.dbtower.operator.model;

import java.util.List;

/**
 * 승인된 변경 한 문장을 <b>기본 키 범위로 쪼개</b> 실행하는 계획(docs/bulk-change-spec.md).
 *
 * <p>{@link ChangePlan}과 무엇이 다른가: 그쪽은 한 트랜잭션 안에서 행 사본을 잡고 대조한 뒤 커밋해
 * 되돌리기를 사본으로 보장한다. 그 안전의 대가가 행 수에 비례해(10,000행 락 보유 PostgreSQL 67.1ms·
 * MySQL 75.2ms, docs/experiments/change-lock-modes.md) 수십만 행에는 쓸 수 없다. 이 계획은 사본을 잡지 않고
 * 구간마다 따로 커밋하며, 되돌리기는 <b>복원 검증에 성공한 최근 백업</b>을 실행 전 조건으로 걸어 보장한다.
 * 그래서 이 경로는 "부분 적용이 정상"이다 — 취소·실패가 이미 커밋한 배치를 되돌리지 않는다.
 *
 * <p>조건은 승인된 원문의 것을 그대로 쓰고 키 범위만 {@code AND}로 덧붙인다. 파서가 조건을 다시 쓰지 않는다 —
 * 틀리게 다시 쓰면 승인받은 범위와 다른 행이 바뀐다.
 *
 * <p><b>복합 키</b>는 행 값 비교로 자른다(#126). {@code (shop_id, id)}의 다음 구간은
 * {@code (shop_id, id) > (?, ?) AND (shop_id, id) <= (?, ?)}다. 열마다 부등호를 따로 쓰면
 * ({@code shop_id > ? AND id > ?}) 구간이 겹치거나 비어 누락·중복이 생긴다 — 사전순 한 덩이로 비교해야
 * "이 키보다 뒤" 하나의 뜻이 된다. 두 기종 모두 행 값 비교로 인덱스를 탄다 — PostgreSQL은 {@code Index Cond: (ROW(shop_id, id) > ROW(1, 1))}으로
 * 조건이 인덱스에 내려가고, MySQL은 {@code type: index, key: PRIMARY, Using index}로 커버링 스캔이 된다(200절).
 *
 * @param statementHead  승인된 문장에서 {@code WHERE} 앞까지(예: {@code UPDATE t SET note = 'x'})
 * @param whereTail      승인된 문장의 {@code WHERE} 조건(키워드 제외). 조건이 없으면 빈 문자열
 * @param table          대상 테이블(원문 표기)
 * @param keyColumns     배치 경계로 쓸 기본 키 열(키 순서). 키가 없으면 이 경로를 쓸 수 없다
 * @param batchRows      한 배치가 목표로 하는 행 수(경계는 행을 세지 않고 키를 훑어 정한다)
 * @param timeoutSeconds 배치 한 번의 문장·락 대기 상한
 */
public record BulkChangePlan(String statementHead, String whereTail, String table, List<String> keyColumns,
                             int batchRows, int timeoutSeconds) {

    public BulkChangePlan {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("배치 경계로 쓸 기본 키 열이 없다");
        }
        keyColumns = List.copyOf(keyColumns);
    }

    /** 단일 키의 짧은 생성자 — 기존 호출부와 테스트가 그대로 돈다. */
    public BulkChangePlan(String statementHead, String whereTail, String table, String keyColumn,
                          int batchRows, int timeoutSeconds) {
        this(statementHead, whereTail, table, List.of(keyColumn), batchRows, timeoutSeconds);
    }

    /** 키 열을 순서대로 이어 붙인 목록 — {@code id} 또는 {@code shop_id, id}. */
    public String keyList() {
        return String.join(", ", keyColumns);
    }

    /**
     * 사전순 비교 한 쪽 — 단일 키면 {@code id <= ?}, 복합 키면 {@code (shop_id, id) <= (?, ?)}.
     * 열이 하나일 때 괄호를 씌우지 않는 이유는 기종마다 한 열 행 값 비교의 처리가 달라서다.
     */
    public String compare(String operator) {
        if (keyColumns.size() == 1) {
            return keyColumns.get(0) + " " + operator + " ?";
        }
        String placeholders = keyColumns.stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElseThrow();
        return "(" + keyList() + ") " + operator + " (" + placeholders + ")";
    }

    /** 원문 조건과 키 범위를 합친 {@code WHERE} 절 — 조건이 없으면 키 범위만 남는다. */
    public String whereFor(boolean hasLowerBound) {
        String range = (hasLowerBound ? compare(">") + " AND " : "") + compare("<=");
        return whereTail.isBlank() ? range : "(" + whereTail + ") AND " + range;
    }
}
