package io.dbtower.operator.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 형태가 <b>같은 뜻</b>인지 본다(#140·#141).
 *
 * <p>형태를 나눈 이유는 기종차지만, 나눈 뒤 위험한 것은 성능이 아니라 <b>뜻이 갈리는 것</b>이다.
 * 한 기종에서만 구간이 겹치거나 비면 그 기종에서만 중복·누락이 생기고, 배치는 오류 없이 끝난다.
 * 그래서 문자열뿐 아니라 <b>자리표시자 수와 바인딩 수가 맞는지</b>, 그리고 실제 키 목록에 적용했을 때
 * 두 형태가 같은 집합을 고르는지를 확인한다.
 */
class KeyRangeSyntaxTest {

    @Nested
    @DisplayName("키가 하나면 두 형태가 같다 — v1.5.0 단일 키 경로는 바뀌지 않는다")
    class SingleKey {

        @Test
        void sameSql() {
            for (String operator : List.of(">", "<=")) {
                assertThat(KeyRangeSyntax.ROW_VALUE.compare(List.of("id"), operator))
                        .isEqualTo(KeyRangeSyntax.EXPANDED.compare(List.of("id"), operator))
                        .isEqualTo("id " + operator + " ?");
            }
        }

        @Test
        void sameBinds() {
            List<Object> key = List.of(7L);
            assertThat(KeyRangeSyntax.ROW_VALUE.binds(key)).isEqualTo(KeyRangeSyntax.EXPANDED.binds(key));
        }
    }

    @Test
    @DisplayName("복합 키를 펼칠 때 선두 열은 엄격한 부등호다 — <= 를 그대로 쓰면 구간이 겹친다")
    void expandedUsesStrictOperatorOnLeadingColumns() {
        List<String> key = List.of("shop_id", "id");

        assertThat(KeyRangeSyntax.EXPANDED.compare(key, ">"))
                .isEqualTo("((shop_id > ?) OR (shop_id = ? AND id > ?))");
        // (a, b) <= (x, y) 는 a < x OR (a = x AND b <= y) 다.
        // a <= x 로 적으면 a = x AND b > y 인 행까지 들어와 다음 배치와 겹친다
        assertThat(KeyRangeSyntax.EXPANDED.compare(key, "<="))
                .isEqualTo("((shop_id < ?) OR (shop_id = ? AND id <= ?))");
    }

    @Test
    @DisplayName("자리표시자 수와 바인딩 수가 맞는다 — 어긋나면 드라이버가 파라미터 오류를 낸다")
    void placeholderCountMatchesBindCount() {
        for (KeyRangeSyntax syntax : KeyRangeSyntax.values()) {
            for (int columns = 1; columns <= 3; columns++) {
                List<String> keyColumns = List.of("a", "b", "c").subList(0, columns);
                List<Object> key = List.<Object>of(1, 2, 3).subList(0, columns);
                for (String operator : List.of(">", "<=")) {
                    long placeholders = syntax.compare(keyColumns, operator).chars().filter(c -> c == '?').count();
                    assertThat(syntax.binds(key))
                            .as("%s %d열 %s", syntax, columns, operator)
                            .hasSize((int) placeholders);
                }
            }
        }
    }

    @Test
    @DisplayName("펼친 형태의 바인딩은 키의 앞부분을 거듭 쓴다")
    void expandedBindsRepeatKeyPrefixes() {
        assertThat(KeyRangeSyntax.EXPANDED.binds(List.of(4, 40000))).containsExactly(4, 4, 40000);
        assertThat(KeyRangeSyntax.EXPANDED.binds(List.of(1, 2, 3))).containsExactly(1, 1, 2, 1, 2, 3);
        assertThat(KeyRangeSyntax.ROW_VALUE.binds(List.of(4, 40000))).containsExactly(4, 40000);
    }

    @Test
    @DisplayName("같은 키 목록에서 두 형태가 같은 행을 고른다 — 뜻이 같다는 것의 실제 확인")
    void bothFormsSelectTheSameRows() {
        // (shop_id, id) 3 x 4 = 12개 키. 경계를 모든 키에 걸쳐 옮겨 가며 두 형태의 결과를 맞춘다
        List<List<Integer>> keys = new java.util.ArrayList<>();
        for (int shop = 1; shop <= 3; shop++) {
            for (int id = 1; id <= 4; id++) {
                keys.add(List.of(shop, id));
            }
        }
        for (List<Integer> from : keys) {
            for (List<Integer> to : keys) {
                List<List<Integer>> rowValue = keys.stream()
                        .filter(k -> lexicographic(k, from) > 0 && lexicographic(k, to) <= 0).toList();
                List<List<Integer>> expanded = keys.stream()
                        .filter(k -> expandedGreater(k, from) && expandedAtMost(k, to)).toList();
                assertThat(expanded).as("구간 (%s, %s]", from, to).isEqualTo(rowValue);
            }
        }
    }

    private static int lexicographic(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < left.size(); i++) {
            int c = Integer.compare(left.get(i), right.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** {@code (a > x) OR (a = x AND b > y)} — SQL이 하는 것을 자바로 그대로 쓴다. */
    private static boolean expandedGreater(List<Integer> key, List<Integer> bound) {
        return key.get(0) > bound.get(0)
                || (key.get(0).equals(bound.get(0)) && key.get(1) > bound.get(1));
    }

    /** {@code (a < x) OR (a = x AND b <= y)} — 선두가 엄격한 부등호인 것이 요점이다. */
    private static boolean expandedAtMost(List<Integer> key, List<Integer> bound) {
        return key.get(0) < bound.get(0)
                || (key.get(0).equals(bound.get(0)) && key.get(1) <= bound.get(1));
    }
}
