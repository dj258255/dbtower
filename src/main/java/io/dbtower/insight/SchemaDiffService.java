package io.dbtower.insight;

import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.ForeignKey;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.SchemaDefinition;
import io.dbtower.operator.model.SchemaDefinitions;
import io.dbtower.operator.model.TableSchema;
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

    /** 외래키 변경 — 같은 이름인데 열·참조 대상·참조 동작이 다르다(153절) */
    public record ForeignKeyChange(String name, ForeignKey left, ForeignKey right) {
    }

    public record DefinitionChange(String name, SchemaDefinition left, SchemaDefinition right) {
    }

    public record DefinitionDiff(List<SchemaDefinition> added, List<SchemaDefinition> removed,
                                 List<DefinitionChange> changed) {
        boolean hasChange() {
            return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        }
    }

    /** 한 테이블 안의 차이 — 양쪽에 존재하는 테이블에 대해서만 채워진다 */
    public record TableDiff(String table,
                            List<ColumnSchema> addedColumns, List<ColumnSchema> removedColumns,
                            List<ColumnChange> changedColumns,
                            List<IndexSchema> addedIndexes, List<IndexSchema> removedIndexes,
                            List<IndexChange> changedIndexes,
                            List<ForeignKey> addedForeignKeys, List<ForeignKey> removedForeignKeys,
                            List<ForeignKeyChange> changedForeignKeys,
                            DefinitionDiff checks, DefinitionDiff triggers) {

        public TableDiff(String table, List<ColumnSchema> addedColumns, List<ColumnSchema> removedColumns,
                         List<ColumnChange> changedColumns, List<IndexSchema> addedIndexes,
                         List<IndexSchema> removedIndexes, List<IndexChange> changedIndexes,
                         List<ForeignKey> addedForeignKeys, List<ForeignKey> removedForeignKeys,
                         List<ForeignKeyChange> changedForeignKeys) {
            this(table, addedColumns, removedColumns, changedColumns, addedIndexes, removedIndexes, changedIndexes,
                    addedForeignKeys, removedForeignKeys, changedForeignKeys,
                    new DefinitionDiff(List.of(), List.of(), List.of()), new DefinitionDiff(List.of(), List.of(), List.of()));
        }

        /** 외래키를 다루지 않는 호출(153절 이전에 만든 기록·단위 테스트) — 외래키 목록은 비어 있다 */
        public TableDiff(String table, List<ColumnSchema> addedColumns, List<ColumnSchema> removedColumns,
                         List<ColumnChange> changedColumns, List<IndexSchema> addedIndexes,
                         List<IndexSchema> removedIndexes, List<IndexChange> changedIndexes) {
            this(table, addedColumns, removedColumns, changedColumns, addedIndexes, removedIndexes, changedIndexes,
                    List.of(), List.of(), List.of());
        }
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
                             List<TableDiff> changedTables, boolean complete) {
        public SchemaDiff(String leftType, String rightType, boolean identical, String warning,
                          List<TableSchema> addedTables, List<TableSchema> removedTables,
                          List<TableDiff> changedTables) {
            this(leftType, rightType, identical, warning, addedTables, removedTables, changedTables, false);
        }
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
                warning(left, right), added, removed, changed, complete(left) && complete(right)
                        && Objects.equals(left.type(), right.type()));
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
        // 외래키(153절) — 제약 이름 기준. 이것이 없으면 외래키만 추가한 변경의 실행 기록이 "구조 차이 없음"으로 남는다
        Map<String, ForeignKey> leftFks = foreignKeysByName(left.foreignKeys());
        Map<String, ForeignKey> rightFks = foreignKeysByName(right.foreignKeys());
        List<ForeignKey> addedFks = new ArrayList<>();
        List<ForeignKey> removedFks = new ArrayList<>();
        List<ForeignKeyChange> changedFks = new ArrayList<>();
        for (Map.Entry<String, ForeignKey> e : rightFks.entrySet()) {
            if (!leftFks.containsKey(e.getKey())) {
                addedFks.add(e.getValue());
            }
        }
        for (Map.Entry<String, ForeignKey> e : leftFks.entrySet()) {
            ForeignKey r = rightFks.get(e.getKey());
            if (r == null) {
                removedFks.add(e.getValue());
            } else if (!sameForeignKey(e.getValue(), r)) {
                changedFks.add(new ForeignKeyChange(e.getKey(), e.getValue(), r));
            }
        }

        return new TableDiff(table, addedCols, removedCols, changedCols,
                addedIdx, removedIdx, changedIdx, addedFks, removedFks, changedFks,
                diffDefinitions(left.checks(), right.checks()), diffDefinitions(left.triggers(), right.triggers()));
    }

    private static DefinitionDiff diffDefinitions(SchemaDefinitions left, SchemaDefinitions right) {
        if (left.status() != SchemaDefinitions.Status.AVAILABLE || right.status() != SchemaDefinitions.Status.AVAILABLE) {
            return new DefinitionDiff(List.of(), List.of(), List.of());
        }
        Map<String, SchemaDefinition> before = new LinkedHashMap<>();
        Map<String, SchemaDefinition> after = new LinkedHashMap<>();
        left.definitions().forEach(d -> before.put(d.name(), d));
        right.definitions().forEach(d -> after.put(d.name(), d));
        List<SchemaDefinition> added = new ArrayList<>();
        List<SchemaDefinition> removed = new ArrayList<>();
        List<DefinitionChange> changed = new ArrayList<>();
        after.forEach((name, value) -> {
            if (!before.containsKey(name)) added.add(value);
        });
        before.forEach((name, value) -> {
            if (!after.containsKey(name)) removed.add(value);
            else if (!value.equals(after.get(name))) changed.add(new DefinitionChange(name, value, after.get(name)));
        });
        return new DefinitionDiff(added, removed, changed);
    }

    private static boolean complete(SchemaSnapshot snapshot) {
        return !snapshot.truncated() && snapshot.tables().stream().allMatch(t ->
                t.checks().status() == SchemaDefinitions.Status.AVAILABLE
                        && t.triggers().status() == SchemaDefinitions.Status.AVAILABLE);
    }

    private static boolean sameIndex(IndexSchema a, IndexSchema b) {
        return a.unique() == b.unique() && Objects.equals(a.columns(), b.columns());
    }

    /** 이름이 같아도 가리키는 대상·열 짝·참조 동작이 달라질 수 있다(ON DELETE CASCADE로 바꾸는 변경 등) */
    private static boolean sameForeignKey(ForeignKey a, ForeignKey b) {
        return Objects.equals(a.columns(), b.columns())
                && Objects.equals(a.refTable(), b.refTable())
                && Objects.equals(a.refColumns(), b.refColumns())
                && Objects.equals(a.onDelete(), b.onDelete())
                && Objects.equals(a.onUpdate(), b.onUpdate());
    }

    private static boolean hasChange(TableDiff td) {
        return !td.addedColumns().isEmpty() || !td.removedColumns().isEmpty()
                || !td.changedColumns().isEmpty() || !td.addedIndexes().isEmpty()
                || !td.removedIndexes().isEmpty() || !td.changedIndexes().isEmpty()
                || !td.addedForeignKeys().isEmpty() || !td.removedForeignKeys().isEmpty()
                || !td.changedForeignKeys().isEmpty() || td.checks().hasChange() || td.triggers().hasChange();
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
        definitionWarnings(left, "왼쪽", notes);
        definitionWarnings(right, "오른쪽", notes);
        return notes.isEmpty() ? null : String.join(" / ", notes);
    }

    private static void definitionWarnings(SchemaSnapshot snapshot, String side, List<String> notes) {
        snapshot.tables().stream().flatMap(t -> java.util.stream.Stream.of(t.checks(), t.triggers()))
                .filter(d -> d.status() != SchemaDefinitions.Status.AVAILABLE)
                .map(d -> side + " CHECK·트리거 부분 비교: " + d.status() + " (" + d.note() + ")")
                .distinct().forEach(notes::add);
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

    private static Map<String, ForeignKey> foreignKeysByName(List<ForeignKey> keys) {
        Map<String, ForeignKey> m = new LinkedHashMap<>();
        if (keys != null) {
            keys.forEach(fk -> m.put(fk.name(), fk));
        }
        return m;
    }
}
