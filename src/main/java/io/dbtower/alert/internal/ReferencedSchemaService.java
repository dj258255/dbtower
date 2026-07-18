package io.dbtower.alert.internal;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.operator.model.TableDetail.DdlSource;
import io.dbtower.operator.model.TableDetail.IndexDetail;
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
 * 자동 탈락, notFound로 정직 표기), 존재가 확인된 테이블은 tableDetail로 승격해 행수·데이터/인덱스 크기·
 * 인덱스 타입·카디널리티까지 얹는다. tableDetail이 실패하면 describeSchema+tableStats 요약으로 폴백.
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

    /** type·cardinality는 tableDetail이 준 값(기종별 미확보면 null) — 선택도 판단 재료 */
    public record RefIndex(String name, List<String> columns, boolean unique, String type, Long cardinality) {
    }

    /**
     * rowCountApprox/dataBytes/indexBytes < 0 이면 미확보(tableStats 상한 밖, tableDetail 실패 등).
     * ddl은 기종별 원천 그대로(MySQL=SHOW CREATE TABLE, PG/MSSQL=카탈로그 재구성, Mongo=JSON) —
     * ddlSource(NATIVE/RECONSTRUCTED)로 출처를 함께 실어 렌더 측이 정직하게 라벨링한다.
     */
    public record RefTable(String name, long rowCountApprox, long dataBytes, long indexBytes,
                           String ddl, String ddlSource, List<RefColumn> columns, List<RefIndex> indexes) {
    }

    /** notFound = SQL엔 있으나 스키마에 없던 후보(CTE·별칭·상한 밖·오탈자). truncated = 스키마 상한에 걸림 */
    public record ReferencedSchema(List<RefTable> tables, List<String> notFound, boolean truncated) {
    }

    /** 테이블 하나의 상세(DDL·통계·인덱스 카디널리티) — 상세 패널 아코디언·문의 첨부의 원천(심화 아크 3) */
    public TableDetail tableDetail(Long instanceId, String table) {
        return operatorFactory.create(registryService.findById(instanceId)).tableDetail(table);
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
            tables.add(enrich(operator, t, rowCounts));
        }
        return new ReferencedSchema(tables, notFound, snapshot.truncated());
    }

    /**
     * 테이블 상세(tableDetail)를 우선 원천으로 쓴다 — describeSchema 요약보다 정확하다.
     * 특히 파티션 부모는 describeSchema/tableStats 경로에서 행수 0·인덱스 누락으로 오판된 전례가 있어
     * (V18 파티셔닝 이후 문의 첨부 회귀), 리프 합산을 아는 tableDetail이 정답이다.
     * tableDetail 실패 시엔 기존 요약 경로로 폴백한다(첨부가 문의를 막지 않는 원칙 그대로).
     */
    private RefTable enrich(DbmsOperator operator, TableSchema t, Map<String, Long> rowCounts) {
        long rows = rowCounts.getOrDefault(t.name().toLowerCase(Locale.ROOT), -1L);
        TableDetail detail = null;
        try {
            detail = operator.tableDetail(t.name());
        } catch (RuntimeException e) {
            // 상세 실패는 요약 폴백으로 흡수 — 구조 요약만으로도 첨부는 유효하다
        }
        if (detail == null || detail.ddlSource() == DdlSource.UNSUPPORTED) {
            return new RefTable(t.name(), rows, -1, -1, null, null, columns(t.columns()), indexes(t.indexes()));
        }
        long detailRows = detail.rowCount() >= 0 ? detail.rowCount() : rows;
        List<RefIndex> idx = detail.indexes().isEmpty() ? indexes(t.indexes()) : fromDetail(detail.indexes());
        return new RefTable(t.name(), detailRows, detail.dataBytes(), detail.indexBytes(),
                detail.ddl(), detail.ddlSource().name(), columns(t.columns()), idx);
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

    /**
     * 인덱스 중심 요약 텍스트 — 문의 embed·본문 공용. 행수에 데이터·인덱스 크기를 얹고,
     * 인덱스는 유니크·타입·카디널리티(확보 시)까지 — 테이블 상세 화면과 같은 재료를 텍스트로 압축한다.
     * DDL 전문은 embed 필드 한도(1024자) 밖이라 콘솔 상세 패널(진단 딥링크)로 위임한다.
     */
    public static String formatCompact(ReferencedSchema schema) {
        if (schema.tables().isEmpty() && schema.notFound().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RefTable t : schema.tables()) {
            sb.append(t.name());
            String stats = formatStats(t);
            if (!stats.isEmpty()) {
                sb.append(" (").append(stats).append(")");
            }
            sb.append('\n');
            if (!t.indexes().isEmpty()) {
                sb.append("  idx: ");
                sb.append(String.join(", ", t.indexes().stream()
                        .map(ReferencedSchemaService::formatIndex)
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

    /** 기본 통계 한 줄 — "≈ N행 · 데이터 X · 인덱스 Y". 미확보(-1) 항목은 생략, 전부 미확보면 빈 문자열. */
    public static String formatStats(RefTable t) {
        List<String> facts = new ArrayList<>();
        if (t.rowCountApprox() >= 0) {
            facts.add("≈ " + String.format(Locale.ROOT, "%,d", t.rowCountApprox()) + "행");
        }
        if (t.dataBytes() >= 0) {
            facts.add("데이터 " + humanBytes(t.dataBytes()));
        }
        if (t.indexBytes() >= 0) {
            facts.add("인덱스 " + humanBytes(t.indexBytes()));
        }
        return String.join(" · ", facts);
    }

    /** 인덱스 하나의 표기 — 이름[U](컬럼들) 타입·card≈카디널리티. 미확보 항목은 표기 자체를 생략(위장 금지). */
    private static String formatIndex(RefIndex i) {
        StringBuilder sb = new StringBuilder(i.name());
        if (i.unique()) {
            sb.append("[U]");
        }
        sb.append("(").append(String.join(",", i.columns())).append(")");
        if (i.type() != null) {
            sb.append(" ").append(i.type());
        }
        if (i.cardinality() != null) {
            sb.append("·card≈").append(String.format(Locale.ROOT, "%,d", i.cardinality()));
        }
        return sb.toString();
    }

    /** 1024 진법, 소수 한 자리 — 콘솔 fmtBytes와 같은 표기 규칙 */
    private static String humanBytes(long bytes) {
        double n = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (n >= 1024 && u < units.length - 1) {
            n /= 1024;
            u++;
        }
        return u == 0 ? bytes + "B" : String.format(Locale.ROOT, "%.1f%s", n, units[u]);
    }

    private static List<RefColumn> columns(List<ColumnSchema> cols) {
        List<RefColumn> out = new ArrayList<>();
        for (ColumnSchema c : cols) {
            out.add(new RefColumn(c.name(), c.type(), c.nullable()));
        }
        return out;
    }

    /** describeSchema 요약 경로의 폴백 변환 — 타입·카디널리티는 이 경로에선 미확보(null) */
    private static List<RefIndex> indexes(List<IndexSchema> idx) {
        List<RefIndex> out = new ArrayList<>();
        for (IndexSchema i : idx) {
            out.add(new RefIndex(i.name(), i.columns(), i.unique(), null, null));
        }
        return out;
    }

    private static List<RefIndex> fromDetail(List<IndexDetail> idx) {
        List<RefIndex> out = new ArrayList<>();
        for (IndexDetail i : idx) {
            out.add(new RefIndex(i.name(), i.columns(), i.unique(), i.type(), i.cardinality()));
        }
        return out;
    }
}
