package io.dbtower.insight;

import io.dbtower.operator.ColumnSchema;
import io.dbtower.operator.IndexSchema;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.operator.TableSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 스키마 diff (B7) — 같은 역할의 두 인스턴스(스테이징 vs 운영, 같은 기종 두 대) 구조를 비교해
 * "왜 저 장비만 다르지"를 테이블·컬럼·인덱스 수준에서 드러낸다.
 *
 * 분류는 left 기준의 3분류다: left에만 있으면 삭제(removed), right에만 있으면 추가(added),
 * 양쪽에 있으나 다르면 변경(changed). left/right는 호출자가 정한 비교 기준(보통 left=베이스라인).
 *
 * 이 서비스는 operator가 읽어 온 SchemaSnapshot 두 개만 받아 순수 비교만 한다(대상 DB 접근 없음) —
 * operator에 스키마 읽기, insight에 비교 로직을 두어 모듈 경계와 순환 없음을 지킨다.
 */
@Service
public class SchemaDiffService {

    /** 컬럼 변경 — 같은 이름인데 타입이나 nullable이 다르다 */
    public record ColumnChange(String name, String leftType, String rightType,
                               boolean leftNullable, boolean rightNullable) {
    }

    /** 인덱스 변경 — 같은 이름인데 구성 컬럼이나 유니크 여부가 다르다 */
    public record IndexChange(String name, IndexSchema left, IndexSchema right) {
    }

    /** 한 테이블 안의 차이 — 양쪽에 존재하는 테이블에 대해서만 채워진다 */
    public record TableDiff(String table,
                            List<ColumnSchema> addedColumns, List<ColumnSchema> removedColumns,
                            List<ColumnChange> changedColumns,
                            List<IndexSchema> addedIndexes, List<IndexSchema> removedIndexes,
                            List<IndexChange> changedIndexes) {
    }

    /**
     * 전체 diff 결과.
     * @param identical    구조 차이가 하나도 없으면 true
     * @param warning      기종이 다르거나 스냅샷이 상한에 잘렸을 때의 주의 문구(없으면 null)
     * @param addedTables  right에만 있는 테이블(전체 구조 포함)
     * @param removedTables left에만 있는 테이블
     * @param changedTables 양쪽에 있으나 컬럼/인덱스가 다른 테이블
     */
    public record SchemaDiff(String leftType, String rightType, boolean identical, String warning,
                             List<TableSchema> addedTables, List<TableSchema> removedTables,
                             List<TableDiff> changedTables) {
    }

    public SchemaDiff diff(SchemaSnapshot left, SchemaSnapshot right) {
        Map<String, TableSchema> leftTables = byName(left.tables());
        Map<String, TableSchema> rightTables = byName(right.tables());

        List<TableSchema> added = new ArrayList<>();
        List<TableSchema> removed = new ArrayList<>();
        List<TableDiff> changed = new ArrayList<>();

        for (Map.Entry<String, TableSchema> e : rightTables.entrySet()) {
            if (!leftTables.containsKey(e.getKey())) {
                added.add(e.getValue()); // right에만
            }
        }
        for (Map.Entry<String, TableSchema> e : leftTables.entrySet()) {
            TableSchema r = rightTables.get(e.getKey());
            if (r == null) {
                removed.add(e.getValue()); // left에만
            } else {
                TableDiff td = diffTable(e.getKey(), e.getValue(), r);
                if (hasChange(td)) {
                    changed.add(td); // 양쪽 다 있으나 차이
                }
            }
        }

        boolean identical = added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        return new SchemaDiff(left.type(), right.type(), identical,
                warning(left, right), added, removed, changed);
    }

    private TableDiff diffTable(String table, TableSchema left, TableSchema right) {
        Map<String, ColumnSchema> leftCols = columnsByName(left.columns());
        Map<String, ColumnSchema> rightCols = columnsByName(right.columns());

        List<ColumnSchema> addedCols = new ArrayList<>();
        List<ColumnSchema> removedCols = new ArrayList<>();
        List<ColumnChange> changedCols = new ArrayList<>();
        for (Map.Entry<String, ColumnSchema> e : rightCols.entrySet()) {
            if (!leftCols.containsKey(e.getKey())) {
                addedCols.add(e.getValue());
            }
        }
        for (Map.Entry<String, ColumnSchema> e : leftCols.entrySet()) {
            ColumnSchema r = rightCols.get(e.getKey());
            if (r == null) {
                removedCols.add(e.getValue());
            } else {
                ColumnSchema l = e.getValue();
                // 순서(ordinalPosition)는 diff 신호로 보지 않는다 — 타입·nullable만 비교
                if (!Objects.equals(l.type(), r.type()) || l.nullable() != r.nullable()) {
                    changedCols.add(new ColumnChange(l.name(), l.type(), r.type(),
                            l.nullable(), r.nullable()));
                }
            }
        }

        Map<String, IndexSchema> leftIdx = indexesByName(left.indexes());
        Map<String, IndexSchema> rightIdx = indexesByName(right.indexes());
        List<IndexSchema> addedIdx = new ArrayList<>();
        List<IndexSchema> removedIdx = new ArrayList<>();
        List<IndexChange> changedIdx = new ArrayList<>();
        for (Map.Entry<String, IndexSchema> e : rightIdx.entrySet()) {
            if (!leftIdx.containsKey(e.getKey())) {
                addedIdx.add(e.getValue());
            }
        }
        for (Map.Entry<String, IndexSchema> e : leftIdx.entrySet()) {
            IndexSchema r = rightIdx.get(e.getKey());
            if (r == null) {
                removedIdx.add(e.getValue());
            } else if (!sameIndex(e.getValue(), r)) {
                changedIdx.add(new IndexChange(e.getKey(), e.getValue(), r));
            }
        }
        return new TableDiff(table, addedCols, removedCols, changedCols,
                addedIdx, removedIdx, changedIdx);
    }

    private static boolean sameIndex(IndexSchema a, IndexSchema b) {
        return a.unique() == b.unique() && Objects.equals(a.columns(), b.columns());
    }

    private static boolean hasChange(TableDiff td) {
        return !td.addedColumns().isEmpty() || !td.removedColumns().isEmpty()
                || !td.changedColumns().isEmpty() || !td.addedIndexes().isEmpty()
                || !td.removedIndexes().isEmpty() || !td.changedIndexes().isEmpty();
    }

    /** 기종 차이·상한 절단은 diff 해석을 왜곡할 수 있어, 있으면 정직하게 경고로 싣는다 */
    private static String warning(SchemaSnapshot left, SchemaSnapshot right) {
        List<String> notes = new ArrayList<>();
        if (!Objects.equals(left.type(), right.type())) {
            notes.add("기종이 다릅니다(" + left.type() + " vs " + right.type()
                    + ") — 같은 논리 타입도 표기가 달라 '컬럼 변경'으로 보일 수 있습니다");
        }
        if (left.truncated() || right.truncated()) {
            notes.add("테이블 상한(" + left.tableCap() + ")에 걸려 일부 테이블이 잘려 부분 비교입니다");
        }
        return notes.isEmpty() ? null : String.join(" / ", notes);
    }

    private static Map<String, TableSchema> byName(List<TableSchema> tables) {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        tables.forEach(t -> m.put(t.name(), t));
        return m;
    }

    private static Map<String, ColumnSchema> columnsByName(List<ColumnSchema> columns) {
        Map<String, ColumnSchema> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c.name(), c));
        return m;
    }

    private static Map<String, IndexSchema> indexesByName(List<IndexSchema> indexes) {
        Map<String, IndexSchema> m = new LinkedHashMap<>();
        indexes.forEach(idx -> m.put(idx.name(), idx));
        return m;
    }
}
