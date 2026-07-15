package io.dbtower.alert.internal;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.operator.model.TableStat;
import io.dbtower.registry.RegistryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 문의·진단에 붙일 "이 쿼리가 참조하는 테이블들의 구조" (심화 아크 2, I1·I3).
 *
 * SQL에서 뽑은 후보 테이블명을 describeSchema 결과와 교집합으로 검증하고(존재하지 않는 후보=CTE·별칭은
 * 자동 탈락, notFound로 정직 표기), 인덱스 중심 요약에 tableStats의 대략 행수("≈")를 얹는다.
 *
 * 스코프: 전용 describeTables(IN-조회) 대신 기존 describeSchema를 재사용해 필터한다. 대부분의 스키마는
 * describeSchema 상한(200) 안이라 실효는 같고, 상한 밖 테이블은 notFound로 드러낸다(향후 최적화 여지).
 */
@Service
public class ReferencedSchemaService {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;

    public ReferencedSchemaService(RegistryService registryService, DbmsOperatorFactory operatorFactory) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
    }

    /** 컬럼은 타입·NULL 여부까지 — 조인 컬럼 타입 불일치 진단을 위해 */
    public record RefColumn(String name, String type, boolean nullable) {
    }

    public record RefIndex(String name, List<String> columns, boolean unique) {
    }

    /** rowCountApprox < 0 이면 행수 미확보(tableStats 상한 밖 등) */
    public record RefTable(String name, long rowCountApprox, List<RefColumn> columns, List<RefIndex> indexes) {
    }

    /** notFound = SQL엔 있으나 스키마에 없던 후보(CTE·별칭·상한 밖·오탈자). truncated = 스키마 상한에 걸림 */
    public record ReferencedSchema(List<RefTable> tables, List<String> notFound, boolean truncated) {
    }

    public ReferencedSchema describe(Long instanceId, String sql) {
        Set<String> referenced = ReferencedTables.from(sql);
        if (referenced.isEmpty()) {
            return new ReferencedSchema(List.of(), List.of(), false);
        }
        DbmsOperator operator = operatorFactory.create(registryService.findById(instanceId));
        SchemaSnapshot snapshot = operator.describeSchema();

        // 이름 -> 테이블 구조 (대소문자 무시 매칭 — 기종마다 케이스 관례가 다르다)
        Map<String, TableSchema> byName = new LinkedHashMap<>();
        for (TableSchema t : snapshot.tables()) {
            byName.put(t.name().toLowerCase(Locale.ROOT), t);
        }
        Map<String, Long> rowCounts = rowCounts(operator);

        List<RefTable> tables = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String name : referenced) {
            TableSchema t = byName.get(name.toLowerCase(Locale.ROOT));
            if (t == null) {
                notFound.add(name);
                continue;
            }
            long rows = rowCounts.getOrDefault(t.name().toLowerCase(Locale.ROOT), -1L);
            tables.add(new RefTable(t.name(), rows, columns(t.columns()), indexes(t.indexes())));
        }
        return new ReferencedSchema(tables, notFound, snapshot.truncated());
    }

    /** tableStats로 대략 행수 맵 — 없거나 실패하면 빈 맵(행수는 부가 정보라 없어도 구조는 보여준다) */
    private static Map<String, Long> rowCounts(DbmsOperator operator) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            for (TableStat s : operator.tableStats(500)) {
                if (s.tableName() != null) {
                    map.put(s.tableName().toLowerCase(Locale.ROOT), s.rowCount());
                }
            }
        } catch (RuntimeException e) {
            // 행수는 부가 정보 — 실패해도 구조 요약은 유효하다
        }
        return map;
    }

    /** 인덱스 중심 요약 텍스트 — 문의 embed·본문 공용. 컬럼은 타입+NULL, 인덱스는 유니크 표기. */
    public static String formatCompact(ReferencedSchema schema) {
        if (schema.tables().isEmpty() && schema.notFound().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RefTable t : schema.tables()) {
            sb.append(t.name());
            if (t.rowCountApprox() >= 0) {
                sb.append(" (≈ ").append(String.format(Locale.ROOT, "%,d", t.rowCountApprox())).append("행)");
            }
            sb.append('\n');
            if (!t.indexes().isEmpty()) {
                sb.append("  idx: ");
                sb.append(String.join(", ", t.indexes().stream()
                        .map(i -> i.name() + (i.unique() ? "[U]" : "") + "(" + String.join(",", i.columns()) + ")")
                        .toList()));
                sb.append('\n');
            } else {
                sb.append("  idx: (없음)\n");
            }
            // 컬럼은 타입 불일치 진단용 — 많으면 앞에서 끊고 나머지 개수 표기
            int shown = Math.min(t.columns().size(), 12);
            sb.append("  cols: ");
            sb.append(String.join(", ", t.columns().subList(0, shown).stream()
                    .map(c -> c.name() + " " + c.type() + (c.nullable() ? "?" : ""))
                    .toList()));
            if (t.columns().size() > shown) {
                sb.append(", … +").append(t.columns().size() - shown);
            }
            sb.append('\n');
        }
        if (!schema.notFound().isEmpty()) {
            sb.append("구조 미확보: ").append(String.join(", ", schema.notFound()));
            if (schema.truncated()) {
                sb.append(" (스키마 상한 초과 가능)");
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private static List<RefColumn> columns(List<ColumnSchema> cols) {
        List<RefColumn> out = new ArrayList<>();
        for (ColumnSchema c : cols) {
            out.add(new RefColumn(c.name(), c.type(), c.nullable()));
        }
        return out;
    }

    private static List<RefIndex> indexes(List<IndexSchema> idx) {
        List<RefIndex> out = new ArrayList<>();
        for (IndexSchema i : idx) {
            out.add(new RefIndex(i.name(), i.columns(), i.unique()));
        }
        return out;
    }
}
