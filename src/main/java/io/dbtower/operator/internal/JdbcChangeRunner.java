package io.dbtower.operator.internal;

import io.dbtower.operator.ChangeCommitUncertainException;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangeOutcome.Probe;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.operator.model.RevertPlan.Conflict;
import io.dbtower.operator.model.RowImage;
import io.dbtower.operator.model.RowImage.ImageColumn;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 승인된 변경의 JDBC 실행 흐름 — 사본 캡처, 실행, 불변식 대조, 커밋, 그리고 사본으로 되돌리기.
 *
 * <p>안전은 문장 해석이 아니라 같은 트랜잭션 안의 불변식에서 나온다: 변경 전 사본을 락과 함께 잡고, 대상 DB가 보고한 영향 행 수가
 * 사본 행 수와 같을 때만 커밋한다. 되돌릴 때는 현재 행이 실행 직후 사본과 같을 때만 쓴다. 기종 차이(락 대기 상한·락 절·
 * 실행계획 문법)는 {@link Dialect}로 받는다 — 흐름에서 기종을 분기하지 않는다.
 */
final class JdbcChangeRunner {

    interface Dialect {
        void beginChange(Statement st, int timeoutSeconds) throws SQLException;

        String lockClause(int timeoutSeconds);

        String explain(Connection c, String sql) throws SQLException;
    }

    private static final int PROBE_RUNS = 3;
    private static final int PROBE_ROW_CAP = 1_000;
    private static final int KEY_BATCH = 100;

    private final Dialect dialect;

    JdbcChangeRunner(Dialect dialect) {
        this.dialect = dialect;
    }

    private record Inserted(long affected, List<List<String>> keys) {
    }

    ChangeOutcome execute(Connection c, ChangePlan plan) throws SQLException {
        long start = System.nanoTime();
        int timeout = Math.max(1, plan.timeoutSeconds());
        String statement = AbstractJdbcOperator.withoutTrailingSemicolon(plan.statement());
        if (plan.dryRun() && plan.kind() == Kind.DDL && c.getMetaData().dataDefinitionCausesTransactionCommit()) {
            throw new OperatorException("이 기종의 DDL은 실행 즉시 커밋돼 드라이런이 곧 실제 실행이 된다. 드라이런하지 않았다");
        }
        c.setAutoCommit(false);
        boolean committed = false;
        try {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(timeout);
                dialect.beginChange(st, timeout);
            }
            Probe probeBefore = plan.probeSql() == null ? null : probe(c, plan.probeSql(), timeout);
            RowImage before = null;
            RowImage after = null;
            String unavailable = null;
            long affected;
            switch (plan.kind()) {
                case UPDATE, DELETE -> {
                    List<String> keys = primaryKey(c, plan.table());
                    before = capture(c, AbstractJdbcOperator.withoutTrailingSemicolon(plan.captureSql())
                            + dialect.lockClause(timeout), plan.maxRows(), keys, timeout);
                    affected = executeUpdate(c, statement, timeout);
                    if (affected != before.rows().size()) {
                        throw new OperatorException("영향 행 수(" + affected + ")가 변경 전 사본 행 수(" + before.rows().size()
                                + ")와 달라 커밋하지 않았다. 사본 조회가 실제 변경 대상과 어긋났거나 동시 변경이 끼었다");
                    }
                    if (before.keyColumns().isEmpty()) {
                        unavailable = "기본 키가 없어 되돌릴 행을 짚을 수 없다";
                    } else {
                        RowImage found = selectByKeys(c, plan.table(), before.columns(), before.keyColumns(),
                                keysOf(before), timeout, false);
                        if (plan.kind() == Kind.UPDATE && found.rows().size() != before.rows().size()) {
                            throw new OperatorException("변경 뒤 같은 기본 키로 찾은 행 수(" + found.rows().size()
                                    + ")가 사본(" + before.rows().size() + ")과 달라 커밋하지 않았다. 기본 키를 바꾸는 UPDATE는 행 대응을 잃는다");
                        }
                        if (plan.kind() == Kind.DELETE && !found.rows().isEmpty()) {
                            throw new OperatorException("삭제 뒤에도 같은 기본 키 행이 남아 커밋하지 않았다");
                        }
                        after = plan.kind() == Kind.UPDATE ? found : RowImage.empty(before.columns(), before.keyColumns());
                    }
                }
                case INSERT -> {
                    List<ImageColumn> columns = describe(c, plan.table(), timeout);
                    List<String> keys = primaryKey(c, plan.table());
                    Inserted inserted = insert(c, statement, keys, timeout);
                    affected = inserted.affected();
                    if (keys.isEmpty()) {
                        unavailable = "기본 키가 없어 되돌릴 행을 짚을 수 없다";
                    } else if (inserted.keys().size() != affected) {
                        unavailable = "대상 DB가 생성된 기본 키를 돌려주지 않았다(키 값을 직접 넣는 INSERT에서 MySQL 드라이버는 키를 돌려주지 않는다)";
                    } else {
                        after = selectByKeys(c, plan.table(), columns, keys, inserted.keys(), timeout, false);
                        if (after.rows().size() != affected) {
                            unavailable = "돌려받은 키로 찾은 행 수가 영향 행 수와 다르다";
                            after = null;
                        }
                    }
                    before = unavailable == null ? RowImage.empty(columns, keys) : null;
                }
                default -> {
                    affected = executeAny(c, statement, timeout);
                    unavailable = plan.kind() == Kind.DDL
                            ? "DDL은 행 사본으로 되돌리지 않는다. 역변경은 새 티켓으로 올린다"
                            : "캡처 없이 실행하기로 사람이 인정한 변경이다";
                }
            }
            Probe probeAfter = plan.probeSql() == null ? null : probe(c, plan.probeSql(), timeout);
            if (plan.dryRun()) {
                c.rollback();
            } else {
                commit(c);
                committed = true;
            }
            return new ChangeOutcome(committed, affected, before, after, unavailable, probeBefore, probeAfter, millis(start));
        } finally {
            if (!committed) {
                rollbackQuietly(c);
            }
        }
    }

    RevertPlan.Outcome revert(Connection c, RevertPlan plan) throws SQLException {
        long start = System.nanoTime();
        int timeout = Math.max(1, plan.timeoutSeconds());
        RowImage reference = plan.originalKind() == Kind.DELETE ? plan.before() : plan.after();
        if (reference == null || reference.keyColumns().isEmpty()) {
            throw new OperatorException("되돌릴 행 사본이 없다");
        }
        c.setAutoCommit(false);
        boolean committed = false;
        try {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(timeout);
                dialect.beginChange(st, timeout);
            }
            List<Conflict> conflicts = conflicts(c, plan, reference, timeout);
            if (!conflicts.isEmpty()) {
                return new RevertPlan.Outcome(false, 0, conflicts, millis(start));
            }
            long restored = switch (plan.originalKind()) {
                case UPDATE -> restoreUpdated(c, plan, timeout);
                case INSERT -> deleteInserted(c, plan, timeout);
                case DELETE -> reinsertDeleted(c, plan, timeout);
                default -> throw new OperatorException(plan.originalKind() + "은 행 사본으로 되돌리지 않는다");
            };
            if (!plan.dryRun()) {
                commit(c);
                committed = true;
            }
            return new RevertPlan.Outcome(committed, restored, List.of(), millis(start));
        } finally {
            if (!committed) {
                rollbackQuietly(c);
            }
        }
    }

    /** 실행 직후 사본과 지금 행을 락을 걸고 대조한다 — 그 사이 누가 바꾼 행이 하나라도 있으면 아무것도 쓰지 않는다. */
    private List<Conflict> conflicts(Connection c, RevertPlan plan, RowImage reference, int timeout) throws SQLException {
        RowImage current = selectByKeys(c, plan.table(), reference.columns(), reference.keyColumns(),
                keysOf(reference), timeout, true);
        Map<List<String>, List<String>> currentByKey = new HashMap<>();
        for (List<String> row : current.rows()) {
            currentByKey.put(current.keyOf(row), row);
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (List<String> row : reference.rows()) {
            List<String> key = reference.keyOf(row);
            List<String> now = currentByKey.get(key);
            if (plan.originalKind() == Kind.DELETE) {
                if (now != null) {
                    conflicts.add(new Conflict(key, List.of(), "삭제했던 키의 행이 다시 생겼다"));
                }
            } else if (now == null) {
                conflicts.add(new Conflict(key, List.of(), "실행 뒤 행이 사라졌다"));
            } else {
                List<String> changed = new ArrayList<>();
                for (int i = 0; i < reference.columns().size(); i++) {
                    String name = reference.columns().get(i).name();
                    int j = current.indexOf(name);
                    if (j < 0 || !Objects.equals(row.get(i), now.get(j))) {
                        changed.add(name);
                    }
                }
                if (!changed.isEmpty()) {
                    conflicts.add(new Conflict(key, changed, "실행 뒤 값이 바뀌었다"));
                }
            }
        }
        return conflicts;
    }

    private long restoreUpdated(Connection c, RevertPlan plan, int timeout) throws SQLException {
        RowImage before = plan.before();
        RowImage after = plan.after();
        Map<List<String>, List<String>> beforeByKey = new HashMap<>();
        for (List<String> row : before.rows()) {
            beforeByKey.put(before.keyOf(row), row);
        }
        String quote = quote(c);
        long restored = 0;
        for (List<String> a : after.rows()) {
            List<String> b = beforeByKey.get(after.keyOf(a));
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < after.columns().size(); i++) {
                boolean isKey = after.keyColumns().stream().anyMatch(after.columns().get(i).name()::equalsIgnoreCase);
                if (!isKey && !Objects.equals(a.get(i), b.get(before.indexOf(after.columns().get(i).name())))) {
                    changed.add(i);
                }
            }
            if (changed.isEmpty()) {
                continue;
            }
            String sql = "UPDATE " + plan.table() + " SET "
                    + changed.stream().map(i -> ident(quote, after.columns().get(i).name()) + " = ?").collect(Collectors.joining(", "))
                    + " WHERE " + keyCondition(quote, after.keyColumns());
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(timeout);
                int idx = 1;
                for (int i : changed) {
                    ImageColumn column = after.columns().get(i);
                    RowValues.bind(ps, idx++, b.get(before.indexOf(column.name())), column);
                }
                idx = bindKey(ps, idx, after, a);
                restored += requireOne(ps.executeUpdate());
            }
        }
        return restored;
    }

    private long deleteInserted(Connection c, RevertPlan plan, int timeout) throws SQLException {
        RowImage after = plan.after();
        String sql = "DELETE FROM " + plan.table() + " WHERE " + keyCondition(quote(c), after.keyColumns());
        long restored = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(timeout);
            for (List<String> row : after.rows()) {
                bindKey(ps, 1, after, row);
                restored += requireOne(ps.executeUpdate());
            }
        }
        return restored;
    }

    private long reinsertDeleted(Connection c, RevertPlan plan, int timeout) throws SQLException {
        RowImage before = plan.before();
        String quote = quote(c);
        String sql = "INSERT INTO " + plan.table() + " ("
                + before.columns().stream().map(col -> ident(quote, col.name())).collect(Collectors.joining(", "))
                + ") VALUES (" + String.join(", ", Collections.nCopies(before.columns().size(), "?")) + ")";
        long restored = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(timeout);
            for (List<String> row : before.rows()) {
                for (int i = 0; i < before.columns().size(); i++) {
                    RowValues.bind(ps, i + 1, row.get(i), before.columns().get(i));
                }
                restored += requireOne(ps.executeUpdate());
            }
        }
        return restored;
    }

    private static long requireOne(int count) {
        if (count != 1) {
            throw new OperatorException("되돌리기 문장 하나가 " + count + "행에 닿아 커밋하지 않았다(키 하나에 정확히 1행이어야 한다)");
        }
        return 1;
    }

    private Probe probe(Connection c, String sql, int timeout) {
        String query = AbstractJdbcOperator.withoutTrailingSemicolon(sql);
        Savepoint savepoint = null;
        try {
            // PostgreSQL은 트랜잭션 안의 오류 하나가 트랜잭션 전체를 망가뜨린다 — 측정 실패가 변경을 죽이지 않게 세이브포인트로 감싼다
            savepoint = c.setSavepoint();
            String plan = dialect.explain(c, query);
            List<Long> timings = new ArrayList<>(PROBE_RUNS);
            for (int run = 0; run < PROBE_RUNS; run++) {
                long t0 = System.nanoTime();
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(timeout);
                    st.setMaxRows(PROBE_ROW_CAP);
                    try (ResultSet rs = st.executeQuery(query)) {
                        while (rs.next()) {
                            // 결과를 끝까지 받아야 실제 응답시간이다
                        }
                    }
                }
                timings.add((System.nanoTime() - t0) / 1_000);
            }
            return new Probe(plan, timings, null);
        } catch (SQLException e) {
            if (savepoint != null) {
                try {
                    c.rollback(savepoint);
                } catch (SQLException ignored) {
                    // 세이브포인트까지 잃었다면 변경 트랜잭션도 곧 실패로 드러난다
                }
            }
            return new Probe(null, List.of(), e.getMessage());
        }
    }

    private RowImage capture(Connection c, String sql, int maxRows, List<String> keys, int timeout) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            st.setMaxRows(maxRows + 1);
            try (ResultSet rs = st.executeQuery(sql)) {
                List<ImageColumn> columns = RowValues.columns(rs.getMetaData());
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        throw new OperatorException("변경 대상이 사본 상한(" + maxRows + "행)을 넘어 실행하지 않았다. 배치로 나누거나 온라인 변경 도구를 써라");
                    }
                    rows.add(RowValues.readRow(rs, columns));
                }
                List<String> present = keys.stream().allMatch(k -> indexOf(columns, k) >= 0) ? keys : List.of();
                return new RowImage(columns, present, rows);
            }
        }
    }

    private List<ImageColumn> describe(Connection c, String table, int timeout) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
                return RowValues.columns(rs.getMetaData());
            }
        }
    }

    private RowImage selectByKeys(Connection c, String table, List<ImageColumn> columns, List<String> keys,
                                  List<List<String>> keyValues, int timeout, boolean lock) throws SQLException {
        String quote = quote(c);
        String condition = "(" + keyCondition(quote, keys) + ")";
        List<ImageColumn> resultColumns = columns;
        List<List<String>> rows = new ArrayList<>();
        RowImage keyed = new RowImage(columns, keys, List.of());
        for (int from = 0; from < keyValues.size(); from += KEY_BATCH) {
            List<List<String>> batch = keyValues.subList(from, Math.min(keyValues.size(), from + KEY_BATCH));
            String sql = "SELECT * FROM " + table + " WHERE " + String.join(" OR ", Collections.nCopies(batch.size(), condition))
                    + (lock ? dialect.lockClause(timeout) : "");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(timeout);
                int idx = 1;
                for (List<String> key : batch) {
                    for (int k = 0; k < keys.size(); k++) {
                        RowValues.bind(ps, idx++, key.get(k), columns.get(keyed.indexOf(keys.get(k))));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    resultColumns = RowValues.columns(rs.getMetaData());
                    while (rs.next()) {
                        rows.add(RowValues.readRow(rs, resultColumns));
                    }
                }
            }
        }
        return new RowImage(resultColumns, keys, rows);
    }

    private Inserted insert(Connection c, String sql, List<String> keys, int timeout) throws SQLException {
        try (PreparedStatement ps = keys.isEmpty() ? c.prepareStatement(sql) : c.prepareStatement(sql, keys.toArray(String[]::new))) {
            ps.setQueryTimeout(timeout);
            long affected = ps.executeUpdate();
            List<List<String>> generated = new ArrayList<>();
            if (keys.isEmpty()) {
                return new Inserted(affected, generated);
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                ResultSetMetaData md = rs.getMetaData();
                int[] index = new int[keys.size()];
                for (int k = 0; k < keys.size(); k++) {
                    index[k] = -1;
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        if (md.getColumnLabel(i).equalsIgnoreCase(keys.get(k))) {
                            index[k] = i;
                        }
                    }
                    // MySQL은 키 이름 대신 GENERATED_KEY 한 열로 돌려준다
                    if (index[k] < 0 && keys.size() == 1 && md.getColumnCount() == 1) {
                        index[k] = 1;
                    }
                    if (index[k] < 0) {
                        return new Inserted(affected, List.of());
                    }
                }
                while (rs.next()) {
                    List<String> key = new ArrayList<>(keys.size());
                    for (int i : index) {
                        key.add(rs.getString(i));
                    }
                    generated.add(key);
                }
            }
            return new Inserted(affected, generated);
        }
    }

    private static long executeUpdate(Connection c, String sql, int timeout) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            return st.executeUpdate(sql);
        }
    }

    private static long executeAny(Connection c, String sql, int timeout) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            st.execute(sql);
            return Math.max(0, st.getUpdateCount());
        }
    }

    /**
     * 기본 키는 드라이버 메타데이터로 찾는다. 스키마를 DML에 쓰는 기종(PostgreSQL·Oracle)은 schema 자리, 카탈로그를 쓰는
     * 기종(MySQL)은 catalog 자리에 한정자를 넣고, 인용하지 않은 이름은 그 기종이 저장하는 대소문자로 맞춘다.
     */
    static List<String> primaryKey(Connection c, String table) throws SQLException {
        if (table == null) {
            return List.of();
        }
        DatabaseMetaData md = c.getMetaData();
        List<String> parts = splitQualified(table);
        String name = identifier(md, parts.get(parts.size() - 1));
        String owner = parts.size() > 1 ? identifier(md, parts.get(parts.size() - 2)) : null;
        String catalog = null;
        String schema = null;
        if (md.supportsSchemasInDataManipulation()) {
            schema = owner != null ? owner : c.getSchema();
        } else {
            catalog = owner != null ? owner : c.getCatalog();
        }
        SortedMap<Short, String> columns = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, name)) {
            while (rs.next()) {
                columns.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(columns.values());
    }

    static List<String> splitQualified(String table) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char open = 0;
        for (char ch : table.strip().toCharArray()) {
            if (open != 0) {
                current.append(ch);
                if (ch == open) {
                    open = 0;
                }
            } else if (ch == '"' || ch == '`') {
                open = ch;
                current.append(ch);
            } else if (ch == '.') {
                parts.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString().strip());
        return parts;
    }

    private static String identifier(DatabaseMetaData md, String raw) throws SQLException {
        if (raw.length() >= 2 && (raw.charAt(0) == '"' || raw.charAt(0) == '`') && raw.charAt(raw.length() - 1) == raw.charAt(0)) {
            return raw.substring(1, raw.length() - 1);
        }
        if (md.storesUpperCaseIdentifiers()) {
            return raw.toUpperCase(java.util.Locale.ROOT);
        }
        return md.storesLowerCaseIdentifiers() ? raw.toLowerCase(java.util.Locale.ROOT) : raw;
    }

    private static String quote(Connection c) throws SQLException {
        String q = c.getMetaData().getIdentifierQuoteString();
        return q == null ? "" : q.strip();
    }

    /** 메타데이터가 준 정확한 열 이름을 인용해 쓴다 — 대소문자 규칙에 기대지 않는다 */
    private static String ident(String quote, String name) {
        return quote.isEmpty() ? name : quote + name.replace(quote, quote + quote) + quote;
    }

    private static String keyCondition(String quote, List<String> keys) {
        return keys.stream().map(k -> ident(quote, k) + " = ?").collect(Collectors.joining(" AND "));
    }

    private static int bindKey(PreparedStatement ps, int start, RowImage image, List<String> row) throws SQLException {
        int idx = start;
        for (String key : image.keyColumns()) {
            int i = image.indexOf(key);
            RowValues.bind(ps, idx++, row.get(i), image.columns().get(i));
        }
        return idx;
    }

    private static List<List<String>> keysOf(RowImage image) {
        return image.rows().stream().map(image::keyOf).toList();
    }

    private static int indexOf(List<ImageColumn> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void commit(Connection c) {
        try {
            c.commit();
        } catch (SQLException e) {
            throw new ChangeCommitUncertainException("커밋 호출이 실패해 대상 DB에 반영됐는지 알 수 없다. 대상 행을 직접 확인해야 한다: "
                    + e.getMessage(), e);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // 커넥션이 깨졌다면 풀이 버린다 — 원래 예외를 롤백 실패가 덮지 않게 한다
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
