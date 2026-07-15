package io.dbtower.operator.internal;

import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 인덱스-컬럼 한 행 — 복합 인덱스는 컬럼 수만큼 여러 행으로 온다(쿼리 정렬 순서 = 인덱스 내 순서) */
    record IndexColumnRow(String table, String indexName, String column, boolean unique) {
    }

    /**
     * 평평한 컬럼/인덱스 행을 테이블 단위로 조립한다. 테이블 순서는 컬럼 행의 등장 순서.
     * maxTables를 넘는 테이블은 버리고 truncated=true로 표시한다(대량 스키마 방어).
     */
    static SchemaSnapshot build(String type, String database,
                                List<ColumnRow> columnRows, List<IndexColumnRow> indexRows,
                                int maxTables) {
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
        for (IndexColumnRow row : indexRows) {
            if (!columnsByTable.containsKey(row.table())) {
                continue; // 상한에 잘린 테이블의 인덱스는 무시
            }
            indexesByTable
                    .computeIfAbsent(row.table(), t -> new LinkedHashMap<>())
                    .computeIfAbsent(row.indexName(), n -> new IndexAccumulator(row.unique()))
                    .columns.add(row.column());
        }

        List<TableSchema> tables = new ArrayList<>();
        for (Map.Entry<String, List<ColumnSchema>> e : columnsByTable.entrySet()) {
            List<IndexSchema> indexes = new ArrayList<>();
            Map<String, IndexAccumulator> perTable = indexesByTable.get(e.getKey());
            if (perTable != null) {
                perTable.forEach((name, acc) ->
                        indexes.add(new IndexSchema(name, List.copyOf(acc.columns), acc.unique)));
            }
            tables.add(new TableSchema(e.getKey(), List.copyOf(e.getValue()), indexes));
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
