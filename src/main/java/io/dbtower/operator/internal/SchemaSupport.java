package io.dbtower.operator.internal;

import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.ForeignKey;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.operator.model.SchemaDefinition;
import io.dbtower.operator.model.SchemaDefinitions;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * describeSchema 공통 조립 로직 (B7). 기종별 Operator는 "평평한 컬럼 행"과 "평평한 인덱스-컬럼 행"만
 * SQL로 뽑아 넘기고, 테이블 단위 묶기·인덱스 컬럼 순서 보존·상위 N개 상한 적용은 여기 한 곳에서 한다.
 *
 * information_schema/statistics류가 이미 (테이블, 순서) 정렬로 내려오므로, LinkedHashMap으로 등장 순서를
 * 보존하면 별도 정렬 없이 컬럼·인덱스 컬럼 순서가 그대로 유지된다.
 */
final class SchemaSupport {

    /**
     * describeSchema가 담는 테이블 상한(하드 상한). 대량 스키마에서 스냅샷·diff가 폭주하지 않게
     * 막는 안전장치 — 이 값을 넘으면 truncated=true로 "부분 뷰"임을 정직하게 알린다.
     * 별도 설정 키를 만들지 않고 상수로 둔 건, 진단용 요약이라 정밀 튜닝 대상이 아니기 때문.
     */
    static final int DEFAULT_MAX_TABLES = 200;

    private SchemaSupport() {
    }

    /** 컬럼 한 행 — 어느 테이블의 컬럼인지 + 컬럼 자체 */
    record ColumnRow(String table, ColumnSchema column) {
    }

    record DefinitionRow(String table, SchemaDefinition definition) {
    }

    record Definitions(Map<String, SchemaDefinitions> tables, SchemaDefinitions fallback) {
        SchemaDefinitions forTable(String table) {
            return tables.getOrDefault(table, fallback);
        }
    }

    static Definitions definitions(Supplier<List<DefinitionRow>> query, String source) {
        try {
            Map<String, List<SchemaDefinition>> grouped = new LinkedHashMap<>();
            query.get().forEach(row -> grouped.computeIfAbsent(row.table(), key -> new ArrayList<>())
                    .add(row.definition()));
            Map<String, SchemaDefinitions> tables = new LinkedHashMap<>();
            grouped.forEach((table, rows) -> tables.put(table,
                    rows.stream().anyMatch(row -> row.definition() == null)
                            ? SchemaDefinitions.unavailable(source + ": 정의 미확보")
                            : new SchemaDefinitions(SchemaDefinitions.Status.AVAILABLE, rows, source)));
            return new Definitions(tables,
                    new SchemaDefinitions(SchemaDefinitions.Status.AVAILABLE, List.of(), source));
        } catch (DataAccessException e) {
            // 메타데이터 권한 부족이 열·인덱스 조회까지 막지 않게 수집 범위를 따로 표시한다.
            return unavailableDefinitions(source + ": 조회 실패, 버전·권한 확인 필요");
        }
    }

    static Definitions unavailableDefinitions(String note) {
        return new Definitions(Map.of(), SchemaDefinitions.unavailable(note));
    }

    /**
     * 인덱스-컬럼 한 행 — 복합 인덱스는 컬럼 수만큼 여러 행으로 온다(쿼리 정렬 순서 = 인덱스 내 순서).
     * primary는 그 인덱스가 기본키인지 — 기본키 인덱스를 가려낼 수 있는 기종은 여기서 기본키 열을 얻는다(150절)
     */
    record IndexColumnRow(String table, String indexName, String column, boolean unique, boolean primary) {
        IndexColumnRow(String table, String indexName, String column, boolean unique) {
            this(table, indexName, column, unique, false);
        }
    }

    /** 종류(TABLE/VIEW)와 기본키를 모르는 기종·호출 — 전부 테이블, 기본키는 인덱스 행의 primary로만 */
    static SchemaSnapshot build(String type, String database,
                                List<ColumnRow> columnRows, List<IndexColumnRow> indexRows,
                                int maxTables) {
        return build(type, database, columnRows, indexRows, Map.of(), Map.of(), maxTables);
    }

    /**
     * 평평한 컬럼/인덱스 행을 테이블 단위로 조립한다. 테이블 순서는 컬럼 행의 등장 순서.
     * maxTables를 넘는 테이블은 버리고 truncated=true로 표시한다(대량 스키마 방어).
     *
     * @param kinds        테이블 이름 -> TABLE/VIEW. 없는 이름은 TABLE
     * @param primaryKeys  테이블 이름 -> 기본키 열. 인덱스로 기본키를 가려낼 수 없는 기종(Oracle 제약조건)이 준다.
     *                     없으면 primary 표시된 인덱스 행의 열을 쓴다
     */
    static SchemaSnapshot build(String type, String database,
                                List<ColumnRow> columnRows, List<IndexColumnRow> indexRows,
                                Map<String, String> kinds, Map<String, List<String>> primaryKeys,
                                int maxTables) {
        return build(type, database, columnRows, indexRows, kinds, primaryKeys, Map.of(), maxTables);
    }

    /**
     * 외래키까지 담는 조립(153절) — 구조 비교가 제약조건 변화를 보려면 스냅샷에 있어야 한다.
     *
     * @param foreignKeys 테이블 이름 -> 그 테이블이 가진 외래키. 상한에 잘린 테이블의 것은 버린다
     */
    static SchemaSnapshot build(String type, String database,
                                List<ColumnRow> columnRows, List<IndexColumnRow> indexRows,
                                Map<String, String> kinds, Map<String, List<String>> primaryKeys,
                                Map<String, List<ForeignKey>> foreignKeys,
                                int maxTables) {
        return build(type, database, columnRows, indexRows, kinds, primaryKeys, foreignKeys,
                unavailableDefinitions("CHECK 미확보"), unavailableDefinitions("트리거 미확보"), maxTables);
    }

    static SchemaSnapshot build(String type, String database,
                                List<ColumnRow> columnRows, List<IndexColumnRow> indexRows,
                                Map<String, String> kinds, Map<String, List<String>> primaryKeys,
                                Map<String, List<ForeignKey>> foreignKeys,
                                Definitions checks, Definitions triggers, int maxTables) {
        // 등장 순서 보존 + 상한 적용. 상한을 넘은 테이블은 포함 집합에 넣지 않는다.
        Map<String, List<ColumnSchema>> columnsByTable = new LinkedHashMap<>();
        boolean truncated = false;
        for (ColumnRow row : columnRows) {
            List<ColumnSchema> cols = columnsByTable.get(row.table());
            if (cols == null) {
                if (columnsByTable.size() >= maxTables) {
                    truncated = true; // 이미 상한 — 새 테이블은 버린다(기존 테이블 컬럼은 계속 채움)
                    continue;
                }
                cols = new ArrayList<>();
                columnsByTable.put(row.table(), cols);
            }
            cols.add(row.column());
        }

        // 인덱스: 포함된 테이블만, (테이블 -> 인덱스명) 순서로 묶고 컬럼은 등장 순서대로
        Map<String, Map<String, IndexAccumulator>> indexesByTable = new LinkedHashMap<>();
        Map<String, List<String>> primaryFromIndexes = new LinkedHashMap<>();
        for (IndexColumnRow row : indexRows) {
            if (!columnsByTable.containsKey(row.table())) {
                continue; // 상한에 잘린 테이블의 인덱스는 무시
            }
            indexesByTable
                    .computeIfAbsent(row.table(), t -> new LinkedHashMap<>())
                    .computeIfAbsent(row.indexName(), n -> new IndexAccumulator(row.unique()))
                    .columns.add(row.column());
            if (row.primary()) {
                primaryFromIndexes.computeIfAbsent(row.table(), t -> new ArrayList<>()).add(row.column());
            }
        }

        List<TableSchema> tables = new ArrayList<>();
        for (Map.Entry<String, List<ColumnSchema>> e : columnsByTable.entrySet()) {
            List<IndexSchema> indexes = new ArrayList<>();
            Map<String, IndexAccumulator> perTable = indexesByTable.get(e.getKey());
            if (perTable != null) {
                perTable.forEach((name, acc) ->
                        indexes.add(new IndexSchema(name, List.copyOf(acc.columns), acc.unique)));
            }
            String kind = TableSchema.VIEW.equals(kinds.get(e.getKey())) ? TableSchema.VIEW : TableSchema.TABLE;
            List<String> primaryKey = primaryKeys.containsKey(e.getKey())
                    ? primaryKeys.get(e.getKey())
                    : primaryFromIndexes.getOrDefault(e.getKey(), List.of());
            tables.add(new TableSchema(e.getKey(), List.copyOf(e.getValue()), indexes, kind, List.copyOf(primaryKey),
                    List.copyOf(foreignKeys.getOrDefault(e.getKey(), List.of())),
                    checks.forTable(e.getKey()), triggers.forTable(e.getKey())));
        }
        return new SchemaSnapshot(type, database, tables, truncated, maxTables);
    }

    private static final class IndexAccumulator {
        private final boolean unique;
        private final List<String> columns = new ArrayList<>();

        private IndexAccumulator(boolean unique) {
            this.unique = unique;
        }
    }
}
