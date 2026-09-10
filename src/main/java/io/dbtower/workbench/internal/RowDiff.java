package io.dbtower.workbench.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 두 결과 집합의 행 단위 차이 — 변경 전후 사본 비교와 인스턴스 간 결과 비교가 같은 규칙을 쓴다.
 *
 * <p>키 열이 있으면 키로 행을 짝지어 열 단위로 무엇이 바뀌었는지 보고, 없거나 키가 유일하지 않으면 행 전체를 하나의 값으로 보고
 * 다중집합으로 비교한다(바뀐 행은 "빠진 행 + 생긴 행"으로 나온다). 값 비교는 표기 차이를 걷어낸 뒤에 한다 —
 * 기종이 다르면 같은 1도 1과 1.00으로, 같은 시각도 "T" 유무로 달리 찍히기 때문이다.
 */
public final class RowDiff {

    public enum ChangeType { ADDED, REMOVED, CHANGED }

    public record Cell(String column, Object left, Object right, boolean changed) {
    }

    public record RowChange(ChangeType type, List<Object> key, List<Cell> cells) {
    }

    /**
     * @param columns    비교에 쓴 열(왼쪽 순서 + 오른쪽에만 있는 열)
     * @param keyColumns 실제로 짝짓기에 쓴 키 열(다중집합 비교로 내려갔으면 빈 목록)
     * @param changes    상한까지의 차이 행
     * @param truncated  차이 행이 상한을 넘어 잘렸는가
     * @param notes      비교를 해석할 때 알아야 할 것(한쪽에만 있는 열, 키 중복으로 인한 방식 전환 등)
     */
    public record Result(List<String> columns, List<String> keyColumns, int leftRows, int rightRows,
                         int added, int removed, int changed, int unchanged, List<RowChange> changes,
                         boolean truncated, List<String> notes) {

        public boolean identical() {
            return added == 0 && removed == 0 && changed == 0;
        }

        Result withNote(String note) {
            List<String> merged = new ArrayList<>(notes);
            merged.add(note);
            return new Result(columns, keyColumns, leftRows, rightRows, added, removed, changed, unchanged, changes,
                    truncated, merged);
        }

        Result withChanges(List<RowChange> replaced) {
            return new Result(columns, keyColumns, leftRows, rightRows, added, removed, changed, unchanged, replaced,
                    truncated, notes);
        }
    }

    private static final Pattern DATETIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}(?::\\d{2})?)(\\.\\d*?)?0*$");

    private RowDiff() {
    }

    static Result diff(List<String> leftColumns, List<List<Object>> leftRows,
                       List<String> rightColumns, List<List<Object>> rightRows,
                       List<String> keyColumns, int maxChanges) {
        List<String> notes = new ArrayList<>();
        List<String> columns = new ArrayList<>(leftColumns);
        for (String rc : rightColumns) {
            if (indexOf(leftColumns, rc) < 0) {
                columns.add(rc);
            }
        }
        List<String> onlyLeft = leftColumns.stream().filter(c -> indexOf(rightColumns, c) < 0).toList();
        List<String> onlyRight = rightColumns.stream().filter(c -> indexOf(leftColumns, c) < 0).toList();
        if (!onlyLeft.isEmpty() || !onlyRight.isEmpty()) {
            notes.add("한쪽에만 있는 열이 있다(왼쪽만: " + onlyLeft + ", 오른쪽만: " + onlyRight + "). 없는 쪽 값은 비어 있는 것으로 본다");
        }
        List<List<Object>> left = align(leftColumns, leftRows, columns);
        List<List<Object>> right = align(rightColumns, rightRows, columns);

        List<String> keys = keyColumns == null ? List.of() : keyColumns.stream()
                .filter(k -> indexOf(columns, k) >= 0).toList();
        if (keyColumns != null && keys.size() != keyColumns.size()) {
            notes.add("결과에 없는 키 열은 뺐다: " + keyColumns.stream().filter(k -> indexOf(columns, k) < 0).toList());
        }
        if (!keys.isEmpty() && (!unique(left, columns, keys) || !unique(right, columns, keys))) {
            notes.add("키 " + keys + "가 유일하지 않아 행 전체 비교로 바꿨다");
            keys = List.of();
        }
        return keys.isEmpty()
                ? byMultiset(columns, left, right, maxChanges, notes)
                : byKey(columns, keys, left, right, maxChanges, notes);
    }

    private static Result byKey(List<String> columns, List<String> keys, List<List<Object>> left,
                                List<List<Object>> right, int max, List<String> notes) {
        Map<List<Object>, List<Object>> rightByKey = new LinkedHashMap<>();
        for (List<Object> row : right) {
            rightByKey.put(normalizedKey(row, columns, keys), row);
        }
        List<RowChange> changes = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int unchanged = 0;
        for (List<Object> l : left) {
            List<Object> r = rightByKey.remove(normalizedKey(l, columns, keys));
            if (r == null) {
                removed++;
                addBounded(changes, max, new RowChange(ChangeType.REMOVED, rawKey(l, columns, keys), cells(columns, l, null)));
                continue;
            }
            List<Cell> cells = cells(columns, l, r);
            if (cells.stream().anyMatch(Cell::changed)) {
                changed++;
                addBounded(changes, max, new RowChange(ChangeType.CHANGED, rawKey(l, columns, keys), cells));
            } else {
                unchanged++;
            }
        }
        for (List<Object> r : rightByKey.values()) {
            added++;
            addBounded(changes, max, new RowChange(ChangeType.ADDED, rawKey(r, columns, keys), cells(columns, null, r)));
        }
        return new Result(columns, keys, left.size(), right.size(), added, removed, changed, unchanged, changes,
                added + removed + changed > changes.size(), notes);
    }

    private static Result byMultiset(List<String> columns, List<List<Object>> left, List<List<Object>> right,
                                     int max, List<String> notes) {
        Map<List<Object>, Integer> remaining = new HashMap<>();
        for (List<Object> r : right) {
            remaining.merge(normalizedRow(r), 1, Integer::sum);
        }
        List<RowChange> changes = new ArrayList<>();
        int removed = 0;
        int unchanged = 0;
        for (List<Object> l : left) {
            List<Object> n = normalizedRow(l);
            Integer count = remaining.get(n);
            if (count != null && count > 0) {
                remaining.put(n, count - 1);
                unchanged++;
            } else {
                removed++;
                addBounded(changes, max, new RowChange(ChangeType.REMOVED, List.of(), cells(columns, l, null)));
            }
        }
        int added = 0;
        for (List<Object> r : right) {
            List<Object> n = normalizedRow(r);
            Integer count = remaining.get(n);
            if (count != null && count > 0) {
                remaining.put(n, count - 1);
                added++;
                addBounded(changes, max, new RowChange(ChangeType.ADDED, List.of(), cells(columns, null, r)));
            }
        }
        return new Result(columns, List.of(), left.size(), right.size(), added, removed, 0, unchanged, changes,
                added + removed > changes.size(), notes);
    }

    private static List<Cell> cells(List<String> columns, List<Object> left, List<Object> right) {
        List<Cell> cells = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Object l = left == null ? null : left.get(i);
            Object r = right == null ? null : right.get(i);
            boolean changed = left != null && right != null && !Objects.equals(normalize(l), normalize(r));
            cells.add(new Cell(columns.get(i), l, r, changed));
        }
        return cells;
    }

    /** 표기 차이만 걷어낸다 — 숫자는 크기로, 날짜시각은 구분자와 끝의 0 소수부를 무시한다. 나머지는 문자열 그대로. */
    static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            try {
                return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return v.toString();
            }
        }
        String s = v.toString();
        Matcher m = DATETIME.matcher(s);
        if (m.matches()) {
            String time = m.group(2).length() == 5 ? m.group(2) + ":00" : m.group(2);
            String fraction = m.group(3) == null || m.group(3).equals(".") ? "" : m.group(3);
            return m.group(1) + " " + time + fraction;
        }
        return s;
    }

    private static List<List<Object>> align(List<String> from, List<List<Object>> rows, List<String> to) {
        int[] index = new int[to.size()];
        for (int i = 0; i < to.size(); i++) {
            index[i] = indexOf(from, to.get(i));
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> aligned = new ArrayList<>(to.size());
            for (int i : index) {
                aligned.add(i < 0 ? null : row.get(i));
            }
            out.add(aligned);
        }
        return out;
    }

    private static boolean unique(List<List<Object>> rows, List<String> columns, List<String> keys) {
        return rows.stream().map(r -> normalizedKey(r, columns, keys)).distinct().count() == rows.size();
    }

    private static List<Object> normalizedKey(List<Object> row, List<String> columns, List<String> keys) {
        List<Object> key = new ArrayList<>(keys.size());
        for (String k : keys) {
            key.add(normalize(row.get(indexOf(columns, k))));
        }
        return key;
    }

    private static List<Object> rawKey(List<Object> row, List<String> columns, List<String> keys) {
        List<Object> key = new ArrayList<>(keys.size());
        for (String k : keys) {
            key.add(row.get(indexOf(columns, k)));
        }
        return key;
    }

    private static List<Object> normalizedRow(List<Object> row) {
        return row.stream().map(RowDiff::normalize).toList();
    }

    private static void addBounded(List<RowChange> changes, int max, RowChange change) {
        if (changes.size() < max) {
            changes.add(change);
        }
    }

    /** 열 이름은 대소문자를 가리지 않는다 — Oracle은 대문자, PostgreSQL은 소문자로 돌려준다. */
    static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
