package io.dbtower.operator.internal;

import io.dbtower.operator.model.QueryResult;
import io.dbtower.operator.model.ResultColumn;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 결과를 콘솔 응답 값으로 바꾼다 — 드라이버 고유 타입(oracle.sql.TIMESTAMP, PGobject 등)이 JSON 직렬화에서
 * 내부 구조를 덤프하지 않게 문자열·숫자·불리언으로 정규화하고, 셀 하나가 응답을 폭주시키지 않게 자른다.
 */
final class JdbcValues {

    static final int CELL_MAX_CHARS = 2_000;

    private JdbcValues() {
    }

    /** 상한까지 읽고, 한 행이 더 있으면 truncated. 호출자는 setMaxRows(상한+1)로 서버 전송량을 먼저 줄여 둔다. */
    static QueryResult read(ResultSet rs, int rowCap, long startNanos) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<ResultColumn> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            columns.add(new ResultColumn(md.getColumnLabel(i), md.getColumnName(i), md.getColumnTypeName(i)));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= rowCap) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                row.add(value(rs, md.getColumnType(i), i));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated, (System.nanoTime() - startNanos) / 1_000_000);
    }

    static Object value(ResultSet rs, int sqlType, int i) throws SQLException {
        switch (sqlType) {
            // 시간 타입은 드라이버 객체 대신 DB가 주는 문자열 표현을 쓴다(Oracle TIMESTAMP 객체의 toString은 쓸모가 없다)
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> {
                return clip(rs.getString(i));
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                byte[] bytes = rs.getBytes(i);
                return bytes == null ? null : "<binary " + bytes.length + " bytes>";
            }
            case Types.BLOB -> {
                // 본문을 메모리로 끌어오지 않는다 — 크기만 알린다
                Blob blob = rs.getBlob(i);
                return blob == null ? null : "<blob " + blob.length() + " bytes>";
            }
            default -> {
            }
        }
        Object v = rs.getObject(i);
        if (v == null || v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte || v instanceof Double || v instanceof Float
                || v instanceof BigDecimal || v instanceof BigInteger) {
            return v;
        }
        if (v instanceof Clob clob) {
            return clip(clob.getSubString(1, (int) Math.min(clob.length(), CELL_MAX_CHARS + 1L)));
        }
        return clip(v.toString());
    }

    static String clip(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > CELL_MAX_CHARS ? s.substring(0, CELL_MAX_CHARS) + "…(잘림)" : s;
    }
}
