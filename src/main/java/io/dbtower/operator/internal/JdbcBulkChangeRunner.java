package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 대량 일괄 변경의 배치 하나 — 경계를 정하고, 그 구간만 고치고, 커밋한다(docs/bulk-change-spec.md).
 *
 * <p>{@link JdbcChangeRunner}와 나뉘는 지점: 그쪽은 한 트랜잭션 안에서 사본·대조·커밋을 묶어 되돌리기를 보장한다.
 * 여기는 되돌리기를 백업에 맡기는 대신 <b>구간마다 따로 커밋</b>해 락 보유를 짧게 끊는다. 그래서 이 클래스가
 * 지켜야 할 불변식은 다르다.
 *
 * <ol>
 *   <li><b>조건을 다시 쓰지 않는다</b> — 승인된 원문의 {@code WHERE}를 그대로 두고 키 범위만 {@code AND}로 덧붙인다.
 *       파서가 조건을 재작성하면 승인받은 범위와 다른 행이 바뀐다</li>
 *   <li><b>한 배치가 목표 행 수를 넘기면 커밋하지 않는다</b> — 경계를 키로 잡았으므로 영향 행 수는 그 구간의 행 수를
 *       넘을 수 없다. 넘었다면 키 열이 유일하지 않거나 조건이 우리가 생각한 것과 다르다는 뜻이라, 롤백하고 멈춘다</li>
 *   <li><b>경계는 행을 세지 않고 키를 훑어 정한다</b> — {@code COUNT(*)}로 미리 세면 그 조회 자체가 대량 스캔이 되고,
 *       세는 사이 행이 바뀌면 경계가 어긋난다</li>
 * </ol>
 *
 * <p>경계 조회에 원문 조건을 함께 건다(pt-archiver와 같은 방식). 그래야 배치마다 실제로 고칠 행이 목표치만큼 들어와
 * 조건에 맞는 행이 드문 테이블에서 빈 배치를 수천 번 돌지 않는다. 대가는 조건 열에 인덱스가 없으면 경계 조회가
 * 넓은 범위를 훑는다는 것이다 — 그 판단은 실행 전 조건(#103)에서 계획을 보고 한다.
 */
final class JdbcBulkChangeRunner {

    interface Dialect {
        /** 문장·락 대기 상한을 건다. {@link JdbcChangeRunner.Dialect#beginChange}와 같은 훅이다. */
        void beginChange(Statement st, int timeoutSeconds) throws SQLException;

        /**
         * 경계 조회에 행 수 제한을 붙인다 — {@code SELECT ... ORDER BY ...} 뒤에 이어 붙일 절.
         *
         * <p>기본은 {@code LIMIT n}(MySQL·PostgreSQL). Oracle은 {@code FETCH FIRST n ROWS ONLY},
         * SQL Server는 {@code TOP (n)}이라 자리가 달라 구현체가 덮어쓴다. SQL Server처럼 절이 앞에 오는
         * 기종은 {@link #selectHead}도 함께 덮어쓴다.
         */
        default String limitClause(int rows) {
            return " LIMIT " + rows;
        }

        /** {@code SELECT} 바로 뒤에 들어갈 것 — SQL Server의 {@code TOP (n)}이 여기 온다. */
        default String selectHead(int rows) {
            return "";
        }
    }

    private final Dialect dialect;

    JdbcBulkChangeRunner(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * 다음 배치가 닫을 구간의 상한 키. 더 고칠 행이 없으면 null.
     *
     * <p>{@code LIMIT}으로 목표 행 수만큼 키를 읽고 <b>마지막 키</b>를 상한으로 쓴다. 읽은 수가 목표에 못 미치면
     * 이번이 마지막 배치다.
     */
    List<Object> nextBoundary(Connection c, BulkChangePlan plan, List<Object> lastKey) {
        boolean hasLower = lastKey != null && !lastKey.isEmpty();
        String where = !hasLower
                ? (plan.whereTail().isBlank() ? "" : " WHERE " + plan.whereTail())
                : " WHERE " + (plan.whereTail().isBlank() ? "" : "(" + plan.whereTail() + ") AND ")
                        + plan.compare(">");
        String sql = "SELECT " + dialect.selectHead(plan.batchRows()) + plan.keyList()
                + " FROM " + plan.table() + where
                + " ORDER BY " + plan.keyList() + dialect.limitClause(plan.batchRows());
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(plan.timeoutSeconds());
            if (hasLower) {
                bind(ps, 1, lastKey);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Object> last = null;
                int columns = plan.keyColumns().size();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getObject(i));
                    }
                    last = row;
                }
                return last;
            }
        } catch (SQLException e) {
            throw new OperatorException("배치 경계 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 키 값을 순서대로 바인딩하고 다음 자리 번호를 돌려준다. */
    private static int bind(PreparedStatement ps, int from, List<Object> key) throws SQLException {
        int i = from;
        for (Object value : key) {
            ps.setObject(i++, value);
        }
        return i;
    }

    /**
     * 구간 {@code (fromKey, toKey]} 하나를 고치고 커밋한다. 영향 행 수가 목표를 넘으면 롤백하고 멈춘다.
     *
     * <p>커넥션의 자동 커밋을 끄고 배치가 끝날 때 되돌린다 — 이 커넥션은 다음 배치에도 쓰인다.
     */
    BulkBatchOutcome executeBatch(Connection c, BulkChangePlan plan, List<Object> fromKey, List<Object> toKey) {
        boolean hasLower = fromKey != null && !fromKey.isEmpty();
        String sql = plan.statementHead() + " WHERE " + plan.whereFor(hasLower);
        boolean autoCommit = true;
        try {
            autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                dialect.beginChange(st, plan.timeoutSeconds());
            }
            long affected;
            long t0 = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(plan.timeoutSeconds());
                int i = 1;
                if (hasLower) {
                    i = bind(ps, i, fromKey);
                }
                bind(ps, i, toKey);
                affected = ps.executeLargeUpdate();
            }
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (affected > plan.batchRows()) {
                c.rollback();
                throw new OperatorException("배치가 목표 행 수를 넘겨 커밋하지 않았다(목표 " + plan.batchRows()
                        + ", 영향 " + affected + ") — 키 열이 유일하지 않거나 조건이 예상과 다르다");
            }
            c.commit();
            return new BulkBatchOutcome(fromKey, toKey, affected, elapsed);
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new OperatorException("배치 실행 실패: " + e.getMessage(), e);
        } finally {
            restoreAutoCommit(c, autoCommit);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // 실패 사유는 위에서 던지는 예외가 들고 간다 — 여기서 덮어쓰지 않는다
        }
    }

    private static void restoreAutoCommit(Connection c, boolean autoCommit) {
        try {
            c.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // 커넥션을 곧 닫거나 풀로 돌려준다 — 복구 실패가 배치 결과를 덮지 않는다
        }
    }
}
