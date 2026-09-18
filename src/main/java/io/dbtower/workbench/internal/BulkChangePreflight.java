package io.dbtower.workbench.internal;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DbmsType;
import io.dbtower.workbench.internal.ChangeStatementParser.Parsed;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;

import java.util.List;
import java.util.Locale;

/**
 * 대량 일괄 변경의 실행 전 조건(docs/bulk-change-spec.md) — 하나라도 어기면 실행하지 않는다.
 *
 * <p>이 검사가 존재하는 이유는 되돌리기 방식이 다르기 때문이다. 소량 변경은 행 사본으로 되돌리지만 대량은
 * <b>복원 검증에 성공한 최근 백업</b>에 기댄다. 그래서 "되돌릴 자리가 있는가"가 기능의 전제이고, 그 확인을
 * 실행 직전에 한다 — 승인 시점에 통과했어도 그 사이 백업이 낡거나 검증이 실패했을 수 있다.
 *
 * <p>승인은 사람이 했지만 조건은 실행 직전에 다시 판정한다. {@code ChangeExecutionService.prepare}가
 * 분류기를 다시 돌리는 것과 같은 이유다 — 승인이 차단 목록을 우회하는 통로가 되면 안 된다.
 */
final class BulkChangePreflight {

    /** 이 경로를 지원하는 기종. 나머지는 Operator에 능력이 붙을 때까지 거부한다. */
    private static final List<DbmsType> SUPPORTED = List.of(DbmsType.MYSQL, DbmsType.POSTGRESQL);

    /** 승인 시점 예상보다 이 배를 넘게 걸리면 멈추고 재승인을 요구한다. */
    static final int ESTIMATE_TOLERANCE = 2;

    private BulkChangePreflight() {
    }

    /**
     * 실행해도 되는지 보고 배치 계획을 만든다. 거부는 예외로 던진다 — 부분 통과를 돌려주면 호출자가
     * 검사 결과를 골라 쓸 여지가 생긴다.
     *
     * @param approvedRows 승인 시점에 티켓에 고정한 예상 영향 행 수
     */
    static BulkChangePlan plan(DbmsType type, String sql, DbmsOperator operator, ConsoleCredential credential,
                               BackupFreshness backup, int batchRows, int timeoutSeconds, long approvedRows) {
        requireSupportedDbms(type);
        requireRestorableBackup(backup);

        Parsed parsed = ChangeStatementParser.parse(sql);
        requireSupportedStatement(parsed);
        String keyColumn = requireSinglePrimaryKey(operator, parsed.table());
        String tail = parsed.captureTail() == null ? "" : parsed.captureTail().strip();
        requireNoOrderOrLimit(tail);

        String where = stripWhereKeyword(tail);
        String head = headOf(sql, tail);
        requireEstimateWithinTolerance(operator, credential, parsed.table(), where, approvedRows, timeoutSeconds);
        return new BulkChangePlan(head, where, parsed.table(), keyColumn, batchRows, timeoutSeconds);
    }

    private static void requireSupportedDbms(DbmsType type) {
        if (!SUPPORTED.contains(type)) {
            throw new WorkbenchRejection(422, "대량 일괄 변경은 아직 MySQL·PostgreSQL에서만 실행합니다(현재 " + type + ")", null);
        }
    }

    /**
     * 되돌리기를 백업에 맡기므로 이것이 전제다. {@code UNSUPPORTED}·{@code FAILED}·검증 전(null)은 모두 거부한다 —
     * "검증하지 않은 백업"과 "복원되는 백업"을 같게 보면 되돌릴 수 없는 변경을 되돌릴 수 있다고 믿고 실행한다.
     */
    private static void requireRestorableBackup(BackupFreshness backup) {
        if (backup == null || backup.status() == BackupFreshness.Status.NO_BACKUP) {
            throw new WorkbenchRejection(409, "복원 검증에 성공한 백업이 없어 대량 변경을 실행하지 않습니다"
                    + " — 되돌리기를 백업에 기대는 경로입니다", null);
        }
        if (backup.status() != BackupFreshness.Status.FRESH) {
            throw new WorkbenchRejection(409, "마지막 백업이 신선도 임계(" + backup.thresholdHours()
                    + "시간)를 넘어 대량 변경을 실행하지 않습니다", null);
        }
        if (!"VERIFIED".equals(backup.verifyStatus())) {
            throw new WorkbenchRejection(409, "마지막 백업의 복원 검증이 확인되지 않아 대량 변경을 실행하지 않습니다"
                    + "(검증 상태 " + (backup.verifyStatus() == null ? "없음" : backup.verifyStatus()) + ")", null);
        }
    }

    private static void requireSupportedStatement(Parsed parsed) {
        if (parsed.kind() != io.dbtower.operator.model.ChangePlan.Kind.UPDATE
                && parsed.kind() != io.dbtower.operator.model.ChangePlan.Kind.DELETE) {
            throw new WorkbenchRejection(422, "대량 일괄 변경은 단일 테이블 UPDATE·DELETE만 지원합니다"
                    + (parsed.reason() == null ? "" : ": " + parsed.reason()), null);
        }
        if (parsed.table() == null || parsed.table().isBlank()) {
            throw new WorkbenchRejection(422, "대상 테이블을 찾지 못해 배치 경계를 정할 수 없습니다", null);
        }
    }

    /**
     * 기본 키가 한 열이어야 한다. 키가 없으면 배치 경계를 정할 수 없어 누락·중복을 막을 수단이 사라지고,
     * 복합 키는 사전순 비교가 필요해 아직 지원하지 않는다(명세의 "하지 않는 것").
     */
    private static String requireSinglePrimaryKey(DbmsOperator operator, String table) {
        TableDetail detail;
        try {
            detail = operator.tableDetail(table);
        } catch (RuntimeException e) {
            throw new WorkbenchRejection(422, "대상 테이블의 기본 키를 확인하지 못해 실행하지 않습니다: " + e.getMessage(), null);
        }
        List<String> pk = detail == null ? List.of() : detail.primaryKey();
        if (pk == null || pk.isEmpty()) {
            throw new WorkbenchRejection(422, "기본 키가 없는 테이블은 배치 경계를 정할 수 없어 실행하지 않습니다", null);
        }
        if (pk.size() > 1) {
            throw new WorkbenchRejection(422, "복합 기본 키(" + String.join(", ", pk) + ")는 아직 지원하지 않습니다", null);
        }
        return pk.get(0);
    }

    /**
     * {@code ORDER BY}·{@code LIMIT}이 붙은 변경은 거부한다. 배치가 키 범위를 오가며 여러 번 도는데 그때마다
     * 정렬·상한이 다시 적용돼, 승인된 문장이 뜻한 "상위 N행"과 실제로 바뀌는 행이 달라진다.
     */
    private static void requireNoOrderOrLimit(String tail) {
        String lower = tail.toLowerCase(Locale.ROOT);
        if (lower.startsWith("order") || lower.startsWith("limit")) {
            throw new WorkbenchRejection(422, "ORDER BY·LIMIT이 붙은 변경은 배치로 나누면 뜻이 달라져 실행하지 않습니다", null);
        }
    }

    /**
     * 승인 시점의 예상과 실행 시점의 실제가 크게 어긋나면 멈춘다. 그 사이 데이터가 늘어 승인받은 것보다
     * 훨씬 많은 행을 고치게 되는 일을 막는다.
     */
    private static void requireEstimateWithinTolerance(DbmsOperator operator, ConsoleCredential credential,
                                                       String table, String where, long approvedRows,
                                                       int timeoutSeconds) {
        if (approvedRows <= 0) {
            return;   // 승인 시점 예상이 없으면 비교하지 않는다 — 없는 값을 지어내 비교하지 않는다
        }
        long actual;
        try {
            actual = operator.countRows(credential, table, where, timeoutSeconds);
        } catch (UnsupportedOperationException e) {
            return;   // 셀 수단이 없는 기종은 이 검사를 건너뛴다. 위 기종 제한이 먼저 걸러낸다
        } catch (RuntimeException e) {
            throw new WorkbenchRejection(422, "영향 행 수를 다시 세지 못해 실행하지 않습니다: " + e.getMessage(), null);
        }
        if (actual > approvedRows * (long) ESTIMATE_TOLERANCE) {
            throw new WorkbenchRejection(409, "실행 시점 영향 행 수(" + actual + ")가 승인 시점 예상("
                    + approvedRows + ")의 " + ESTIMATE_TOLERANCE + "배를 넘어 실행하지 않습니다. 다시 승인받으세요", null);
        }
    }

    /** {@code WHERE x = 1} -> {@code x = 1}. WHERE가 없으면 빈 문자열(조건 없는 전체 변경도 허용한다). */
    private static String stripWhereKeyword(String tail) {
        if (tail.isEmpty()) {
            return "";
        }
        if (tail.length() > 5 && tail.substring(0, 5).equalsIgnoreCase("where")) {
            return tail.substring(5).strip();
        }
        return tail;
    }

    /** 원문에서 조건 앞부분만 — 파서가 조건을 다시 쓰지 않게 잘라내기만 한다. */
    private static String headOf(String sql, String tail) {
        String trimmed = sql.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        if (!tail.isEmpty() && trimmed.endsWith(tail)) {
            return trimmed.substring(0, trimmed.length() - tail.length()).strip();
        }
        return trimmed;
    }
}
