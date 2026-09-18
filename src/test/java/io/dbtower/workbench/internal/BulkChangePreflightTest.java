package io.dbtower.workbench.internal;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DbmsType;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대량 일괄 변경의 실행 전 조건 — 되돌릴 자리가 없거나 배치 경계를 정할 수 없으면 실행하지 않는다.
 *
 * <p>거부 하나하나가 "무엇을 막으려는 것인가"로 이름을 달았다. 조건을 지우거나 완화할 때 무엇이 위험해지는지
 * 다음 사람이 여기서 읽을 수 있어야 한다.
 */
class BulkChangePreflightTest {

    private static final ConsoleCredential CRED = new ConsoleCredential("w", "p");
    private static final String SQL = "UPDATE orders SET status = 'DONE' WHERE status = 'PENDING'";

    private static BackupFreshness backup(BackupFreshness.Status status, String verify) {
        return new BackupFreshness(1L, "live", DbmsType.MYSQL, LocalDateTime.now(), verify, "s3://b",
                1.0, status == BackupFreshness.Status.FRESH, status, 24);
    }

    private static DbmsOperator operatorWithPk(String... pk) {
        DbmsOperator op = mock(DbmsOperator.class);
        TableDetail detail = new TableDetail("orders", "InnoDB", 1000, 0, 0, 0, null, null, null,
                List.of(), null, List.of(pk), List.of(), List.of());
        when(op.tableDetail(anyString())).thenReturn(detail);
        when(op.countRows(any(), anyString(), anyString(), anyInt())).thenReturn(1000L);
        return op;
    }

    private static BulkChangePlan plan(DbmsType type, String sql, DbmsOperator op, BackupFreshness backup,
                                       long approvedRows) {
        return BulkChangePreflight.plan(type, sql, op, CRED, backup, 1000, 30, approvedRows);
    }

    @Test
    @DisplayName("조건이 맞으면 승인된 조건을 그대로 둔 배치 계획을 만든다")
    void buildsPlan() {
        BulkChangePlan p = plan(DbmsType.MYSQL, SQL, operatorWithPk("id"),
                backup(BackupFreshness.Status.FRESH, "VERIFIED"), 1000);

        assertThat(p.statementHead()).isEqualTo("UPDATE orders SET status = 'DONE'");
        assertThat(p.whereTail()).isEqualTo("status = 'PENDING'");   // WHERE 키워드만 떼고 조건은 원문 그대로
        assertThat(p.table()).isEqualTo("orders");
        assertThat(p.keyColumns()).containsExactly("id");
        assertThat(p.whereFor(true)).isEqualTo("(status = 'PENDING') AND id > ? AND id <= ?");
    }

    @Test
    @DisplayName("끝의 세미콜론과 조건 없는 문장도 계획이 된다")
    void trailingSemicolonAndNoCondition() {
        BulkChangePlan p = plan(DbmsType.MYSQL, "DELETE FROM orders;", operatorWithPk("id"),
                backup(BackupFreshness.Status.FRESH, "VERIFIED"), 0);

        assertThat(p.statementHead()).isEqualTo("DELETE FROM orders");
        assertThat(p.whereTail()).isEmpty();
        assertThat(p.whereFor(false)).isEqualTo("id <= ?");
    }

    @Test
    @DisplayName("복원 검증된 최신 백업이 없으면 거부한다 — 되돌리기를 백업에 기대는 경로다")
    void requiresVerifiedFreshBackup() {
        DbmsOperator op = operatorWithPk("id");

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op, null, 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("복원 검증에 성공한 백업이 없어");

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op,
                backup(BackupFreshness.Status.NO_BACKUP, null), 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("복원 검증에 성공한 백업이 없어");

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op,
                backup(BackupFreshness.Status.STALE, "VERIFIED"), 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("신선도 임계");

        // 검증 전(null)과 검증 실패, 검증 불가를 "복원되는 백업"과 같게 보지 않는다
        for (String verify : new String[]{null, "FAILED", "UNSUPPORTED"}) {
            assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op,
                    backup(BackupFreshness.Status.FRESH, verify), 1000))
                    .as("verifyStatus=%s", verify)
                    .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("복원 검증이 확인되지 않아");
        }
    }

    @Test
    @DisplayName("기본 키가 없으면 거부하고, 복합 키는 사전순 비교로 받는다")
    void requiresPrimaryKey() {
        BackupFreshness ok = backup(BackupFreshness.Status.FRESH, "VERIFIED");

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, operatorWithPk(), ok, 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("기본 키가 없는 테이블");

        // 복합 키는 이제 사전순 비교로 받는다(#126) — 거부가 아니라 계획이 나와야 한다
        BulkChangePlan composite = plan(DbmsType.MYSQL, SQL, operatorWithPk("shop_id", "id"), ok, 1000);
        assertThat(composite.keyColumns()).containsExactly("shop_id", "id");
        assertThat(composite.compare(">")).isEqualTo("(shop_id, id) > (?, ?)");
        assertThat(composite.whereFor(true))
                .isEqualTo("(status = 'PENDING') AND (shop_id, id) > (?, ?) AND (shop_id, id) <= (?, ?)");

        // 열이 상한을 넘으면 거부한다 — 경계 조회가 인덱스를 타는지 확인된 범위까지만 받는다
        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL,
                operatorWithPk("a", "b", "c", "d"), ok, 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("기본 키 열이 4개입니다");

        DbmsOperator broken = mock(DbmsOperator.class);
        when(broken.tableDetail(anyString())).thenThrow(new OperatorException("권한 없음"));
        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, broken, ok, 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("기본 키를 확인하지 못해");
    }

    @Test
    @DisplayName("단일 테이블 UPDATE·DELETE가 아니면 거부한다")
    void rejectsUnsupportedStatements() {
        BackupFreshness ok = backup(BackupFreshness.Status.FRESH, "VERIFIED");
        DbmsOperator op = operatorWithPk("id");

        for (String sql : List.of(
                "INSERT INTO orders SELECT * FROM staging",
                "MERGE INTO orders USING staging ON (orders.id = staging.id)",
                "ALTER TABLE orders ADD COLUMN memo VARCHAR(10)",
                "UPDATE orders SET status = 'X' WHERE id IN (SELECT id FROM staging) RETURNING id")) {
            assertThatThrownBy(() -> plan(DbmsType.MYSQL, sql, op, ok, 0))
                    .as("%s", sql)
                    .isInstanceOf(WorkbenchRejection.class)
                    .hasMessageContaining("단일 테이블 UPDATE·DELETE만 지원합니다");
        }
    }

    @Test
    @DisplayName("ORDER BY·LIMIT이 붙은 변경은 거부한다 — 배치로 나누면 뜻이 달라진다")
    void rejectsOrderByAndLimit() {
        BackupFreshness ok = backup(BackupFreshness.Status.FRESH, "VERIFIED");
        DbmsOperator op = operatorWithPk("id");

        assertThatThrownBy(() -> plan(DbmsType.MYSQL,
                "DELETE FROM orders ORDER BY created_at LIMIT 100", op, ok, 0))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("배치로 나누면 뜻이 달라져");
    }

    @Test
    @DisplayName("MySQL·PostgreSQL이 아니면 거부한다")
    void rejectsOtherDbms() {
        BackupFreshness ok = backup(BackupFreshness.Status.FRESH, "VERIFIED");
        DbmsOperator op = operatorWithPk("id");

        for (DbmsType type : List.of(DbmsType.ORACLE, DbmsType.MSSQL, DbmsType.MONGODB)) {
            assertThatThrownBy(() -> plan(type, SQL, op, ok, 1000))
                    .as("%s", type)
                    .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("MySQL·PostgreSQL에서만");
        }
    }

    @Test
    @DisplayName("실행 시점 행 수가 승인 시점 예상의 2배를 넘으면 거부하고, 그 안이면 통과한다")
    void estimateTolerance() {
        BackupFreshness ok = backup(BackupFreshness.Status.FRESH, "VERIFIED");
        DbmsOperator op = operatorWithPk("id");
        when(op.countRows(any(), anyString(), anyString(), anyInt())).thenReturn(2001L);

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op, ok, 1000))
                .isInstanceOf(WorkbenchRejection.class)
                .hasMessageContaining("승인 시점 예상(1000)의 2배를 넘어");

        when(op.countRows(any(), anyString(), anyString(), anyInt())).thenReturn(2000L);
        assertThat(plan(DbmsType.MYSQL, SQL, op, ok, 1000)).isNotNull();
    }

    @Test
    @DisplayName("승인 시점 예상이 없으면 비교하지 않는다 — 없는 값을 지어내 비교하지 않는다")
    void noApprovedEstimateSkipsComparison() {
        DbmsOperator op = operatorWithPk("id");
        when(op.countRows(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new AssertionError("예상이 없으면 세지 않아야 한다"));

        assertThat(plan(DbmsType.MYSQL, SQL, op, backup(BackupFreshness.Status.FRESH, "VERIFIED"), 0))
                .isNotNull();
    }

    @Test
    @DisplayName("행 수를 세지 못하면 거부한다 — 못 센 것을 '괜찮다'로 읽지 않는다")
    void countFailureRejects() {
        DbmsOperator op = operatorWithPk("id");
        when(op.countRows(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new OperatorException("조회 시간 초과"));

        assertThatThrownBy(() -> plan(DbmsType.MYSQL, SQL, op,
                backup(BackupFreshness.Status.FRESH, "VERIFIED"), 1000))
                .isInstanceOf(WorkbenchRejection.class).hasMessageContaining("영향 행 수를 다시 세지 못해");
    }
}
