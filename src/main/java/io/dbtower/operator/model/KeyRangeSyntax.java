package io.dbtower.operator.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 복합 키 구간 {@code (마지막 키, 상한 키]}를 SQL로 적는 두 형태(#140·#141).
 *
 * <p>뜻은 둘이 같다 — 키를 사전순 한 덩이로 보고 "이 키보다 뒤"를 고른다. 형태를 나눈 이유는 기종마다
 * <b>문법 지원과 옵티마이저 처리가 다르기</b> 때문이다. 이 구분이 없으면 한 기종에서 문법 오류로 죽거나,
 * 돌더라도 배치가 뒤로 갈수록 앞 구간을 다시 훑는다(전체 O(n^2)).
 *
 * <p>실측(20만 행, 복합 PK {@code (shop_id, id)}, 경계를 앞 {@code (1,1)}과 뒤 {@code (4,40000)}에서 비교):
 *
 * <table border="1">
 *   <caption>기종별 조건 처리</caption>
 *   <tr><th>기종</th><th>{@code ROW_VALUE} 처리</th><th>앞 경계</th><th>뒤 경계</th></tr>
 *   <tr><td>PostgreSQL 16</td><td>{@code Index Cond: (ROW(shop_id, id) > ROW(4, 40000))}</td>
 *       <td>24 버퍼</td><td>24 버퍼</td></tr>
 *   <tr><td>MySQL 8.4</td><td>{@code Covering index scan} + {@code Filter}</td>
 *       <td>1,001행 0.27ms</td><td>191,000행 30.5ms</td></tr>
 *   <tr><td>Oracle 23</td><td>{@code INDEX FULL SCAN} + filter</td>
 *       <td>152 gets</td><td>897 gets</td></tr>
 *   <tr><td>SQL Server 2022</td><td>문법 없음 — {@code Msg 4145}</td><td colspan="2">실행 불가</td></tr>
 * </table>
 *
 * <p>MySQL은 {@link #EXPANDED}로 적으면 범위 조회가 된다
 * ({@code Covering index range scan over (shop_id = 4 AND 40000 < id) OR (4 < shop_id)}, 200,000행 -> 10,000행,
 * 32.4ms -> 2.6ms). SQL Server는 {@code EXPANDED}만 문법이 성립한다.
 *
 * <p><b>PostgreSQL에 {@code EXPANDED}를 쓰면 안 된다.</b> 비트맵 스캔이 인덱스 순서를 잃어 {@code Sort}가 붙는다
 * (0.60ms 24버퍼 -> 3.19ms 115버퍼). 경계 조회는 {@code ORDER BY 키 LIMIT n}이라 순서가 곧 성능이다.
 *
 * <p>Oracle은 두 형태를 같은 계획으로 정규화한다(plan hash 동일, 술어에 Oracle이 스스로 펼친 것이 보인다).
 * 이 구분으로는 고쳐지지 않아 {@code ROW_VALUE}로 두고 따로 본다(#141).
 *
 * <p>키가 하나면 두 형태가 같은 문자열({@code id > ?})을 낸다 — v1.5.0까지의 단일 키 경로는 바뀌지 않는다.
 */
public enum KeyRangeSyntax {

    /** {@code (a, b) > (?, ?)} — 표준 행 값 비교. PostgreSQL·Oracle이 받는다. */
    ROW_VALUE {
        @Override
        public String compare(List<String> keyColumns, String operator) {
            if (keyColumns.size() == 1) {
                return keyColumns.get(0) + " " + operator + " ?";
            }
            String columns = String.join(", ", keyColumns);
            String placeholders = String.join(", ", keyColumns.stream().map(c -> "?").toList());
            return "(" + columns + ") " + operator + " (" + placeholders + ")";
        }

        @Override
        public List<Object> binds(List<Object> key) {
            return List.copyOf(key);
        }
    },

    /**
     * {@code ((a > ?) OR (a = ? AND b > ?))} — 같은 뜻을 펼친 것. MySQL·SQL Server용.
     *
     * <p>선두 열에는 <b>엄격한</b> 부등호가 붙고 마지막 열에만 원래 부등호가 붙는다.
     * {@code (a, b) <= (x, y)}는 {@code a < x OR (a = x AND b <= y)}이지 {@code a <= x OR ...}가 아니다 —
     * {@code a <= x}로 적으면 {@code a = x AND b > y}인 행까지 구간에 들어와 다음 배치와 겹친다.
     */
    EXPANDED {
        @Override
        public String compare(List<String> keyColumns, String operator) {
            String strict = "<=".equals(operator) ? "<" : operator;
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < keyColumns.size(); i++) {
                StringBuilder term = new StringBuilder();
                for (int j = 0; j < i; j++) {
                    term.append(keyColumns.get(j)).append(" = ? AND ");
                }
                boolean last = i == keyColumns.size() - 1;
                term.append(keyColumns.get(i)).append(' ').append(last ? operator : strict).append(" ?");
                terms.add(keyColumns.size() == 1 ? term.toString() : "(" + term + ")");
            }
            return terms.size() == 1 ? terms.get(0) : "(" + String.join(" OR ", terms) + ")";
        }

        @Override
        public List<Object> binds(List<Object> key) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < key.size(); i++) {
                values.addAll(key.subList(0, i + 1));
            }
            return List.copyOf(values);
        }
    };

    /** 구간 한 쪽을 적는다. 자리표시자 수는 형태마다 다르므로 바인딩은 반드시 {@link #binds}로 만든다. */
    public abstract String compare(List<String> keyColumns, String operator);

    /** {@link #compare}가 낸 자리표시자에 순서대로 넣을 값 — {@code EXPANDED}는 키의 앞부분을 거듭 쓴다. */
    public abstract List<Object> binds(List<Object> key);
}
