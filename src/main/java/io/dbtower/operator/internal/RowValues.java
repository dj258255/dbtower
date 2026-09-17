package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.RowImage.ImageColumn;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 행 사본의 값 부호화 — 읽을 때 문자열로 정규화하고, 되돌릴 때 JDBC 타입으로 복원해 파라미터로 바인딩한다.
 *
 * <p>콘솔 조회용 {@link JdbcValues}와 목적이 다르다. 그쪽은 화면 표시라 잘라도 되지만, 이쪽은 되돌리기의 원본이라 한 글자도
 * 잃으면 안 된다. 그래서 자르는 대신 상한을 넘으면 사본 캡처를 거부하고, 시간 값은 드라이버 문자열 표현이 아니라
 * java.time 값으로 읽어 소수 초를 보존한다.
 */
final class RowValues {

    /** 셀 하나의 사본 상한 — 사본은 플랫폼 DB에 저장되므로 대용량 LOB을 끌어오지 않는다 */
    static final int CELL_MAX_CHARS = 64 * 1024;

    private static final String BINARY_PREFIX = "base64:";
    private static final Pattern OFFSET = Pattern.compile(".*(Z|[+-]\\d{2}:\\d{2}(:\\d{2})?)$");

    private RowValues() {
    }

    static List<ImageColumn> columns(ResultSetMetaData md) throws SQLException {
        List<ImageColumn> columns = new ArrayList<>(md.getColumnCount());
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(new ImageColumn(md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i)));
        }
        return columns;
    }

    static List<String> readRow(ResultSet rs, List<ImageColumn> columns) throws SQLException {
        List<String> row = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            row.add(read(rs, i + 1, columns.get(i)));
        }
        return row;
    }

    static String read(ResultSet rs, int i, ImageColumn column) throws SQLException {
        String value = switch (column.sqlType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE,
                 Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal v = rs.getBigDecimal(i);
                yield v == null ? null : v.toPlainString();
            }
            case Types.DATE -> {
                LocalDate v = rs.getObject(i, LocalDate.class);
                yield v == null ? null : v.toString();
            }
            case Types.TIME -> {
                LocalTime v = rs.getObject(i, LocalTime.class);
                yield v == null ? null : v.toString();
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> timestamp(rs, i);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] v = rs.getBytes(i);
                if (v != null && v.length > CELL_MAX_CHARS) {
                    throw tooLarge(column);
                }
                yield v == null ? null : BINARY_PREFIX + Base64.getEncoder().encodeToString(v);
            }
            default -> rs.getString(i);
        };
        if (value != null && value.length() > CELL_MAX_CHARS + BINARY_PREFIX.length()) {
            throw tooLarge(column);
        }
        return value;
    }

    /** 시간대 없는 시각은 LocalDateTime으로, 시간대 있는 시각(PostgreSQL timestamptz 등)은 드라이버가 거절하면 OffsetDateTime으로 */
    private static String timestamp(ResultSet rs, int i) throws SQLException {
        try {
            LocalDateTime v = rs.getObject(i, LocalDateTime.class);
            return v == null ? null : v.toString();
        } catch (SQLException e) {
            OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
            return v == null ? null : v.toString();
        }
    }

    static void bind(PreparedStatement ps, int index, String value, ImageColumn column) throws SQLException {
        int type = column.sqlType();
        if (value == null) {
            ps.setNull(index, type);
            return;
        }
        switch (type) {
            // 정수 열을 BigDecimal로 보내면 PostgreSQL은 bigint = numeric 비교가 되어 기본 키 인덱스를 못 탄다.
            // 키 재조회·되돌리기가 행마다 전체 스캔이 된다(2만 행 표에서 키 100개 묶음 72ms). long 범위를 넘는 값만
            // (MySQL BIGINT UNSIGNED) BigDecimal로 보낸다
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                try {
                    ps.setLong(index, Long.parseLong(value));
                } catch (NumberFormatException outOfRange) {
                    ps.setBigDecimal(index, new BigDecimal(value));
                }
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL ->
                    ps.setBigDecimal(index, new BigDecimal(value));
            case Types.DATE -> ps.setObject(index, LocalDate.parse(value));
            case Types.TIME -> ps.setObject(index, LocalTime.parse(value));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ps.setObject(index,
                    OFFSET.matcher(value).matches() ? OffsetDateTime.parse(value) : LocalDateTime.parse(value));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    ps.setBytes(index, Base64.getDecoder().decode(value.substring(BINARY_PREFIX.length())));
            // PostgreSQL boolean은 t/f, MySQL TINYINT(1)은 0/1로 온다 — 불리언 단어일 때만 불리언으로 복원한다
            case Types.BIT, Types.BOOLEAN -> {
                if (value.matches("(?i)t|f|true|false")) {
                    ps.setBoolean(index, Character.toLowerCase(value.charAt(0)) == 't');
                } else {
                    ps.setString(index, value);
                }
            }
            // uuid·json·배열처럼 드라이버 고유 타입은 타입을 지정하지 않고 보내 서버가 열 타입으로 해석하게 한다
            case Types.OTHER, Types.ARRAY -> ps.setObject(index, value, Types.OTHER);
            default -> ps.setString(index, value);
        }
    }

    private static OperatorException tooLarge(ImageColumn column) {
        return new OperatorException("열 " + column.name() + "의 값이 사본 상한(" + CELL_MAX_CHARS
                + "자)을 넘어 되돌릴 사본을 남길 수 없다. 캡처 없이 실행하려면 사람이 명시적으로 인정해야 한다");
    }
}
