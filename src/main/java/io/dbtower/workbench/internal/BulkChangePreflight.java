package io.dbtower.workbench.internal;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DbmsType;
import io.dbtower.workbench.internal.ChangeStatementParser.Parsed;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 대량 일괄 변경의 실행 전 조건(docs/design/bulk-change-spec.md) — 하나라도 어기면 실행하지 않는다.
 *
 * <p>이 검사가 존재하는 이유는 되돌리기 방식이 다르기 때문이다. 소량 변경은 행 사본으로 되돌리지만 대량은
 * <b>복원 검증에 성공한 최근 백업</b>에 기댄다. 그래서 "되돌릴 자리가 있는가"가 기능의 전제이고, 그 확인을
 * 실행 직전에 한다 — 승인 시점에 통과했어도 그 사이 백업이 낡거나 검증이 실패했을 수 있다.
 *
 * <p>승인은 사람이 했지만 조건은 실행 직전에 다시 판정한다. {@code ChangeExecutionService.prepare}가
 * 분류기를 다시 돌리는 것과 같은 이유다 — 승인이 차단 목록을 우회하는 통로가 되면 안 된다.
 */
final class BulkChangePreflight {

    /**
     * 이 경로를 지원하는 기종. MongoDB는 {@code _id} 타입이 하나일 때만 받는다(#128 판정) —
     * 섞이면 비교가 타입 경계를 넘지 않아 배치가 문서를 조용히 빼먹는다.
     */
    private static final List<DbmsType> SUPPORTED =
            List.of(DbmsType.MYSQL, DbmsType.POSTGRESQL, DbmsType.ORACLE, DbmsType.MSSQL, DbmsType.MONGODB);

    /** 승인 시점 예상보다 이 배를 넘게 걸리면 멈추고 재승인을 요구한다. */
    static final int ESTIMATE_TOLERANCE = 2;

    /**
     * 경계로 쓸 키 열 수의 상한. 사전순 비교는 열 수와 무관하게 성립하지만, 열이 늘수록 경계 조회가
     * 인덱스를 타는지가 기종·통계에 따라 갈린다. 실측으로 확인한 범위까지만 받는다(#126: 2열까지 확인).
     */
    static final int MAX_KEY_COLUMNS = 3;

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
        List<String> keyColumns = requirePrimaryKey(operator, parsed.table());
        requireKeysNotModified(sql, parsed.kind(), keyColumns);
        String tail = parsed.captureTail() == null ? "" : parsed.captureTail().strip();
        requireNoOrderOrLimit(tail);

        String where = stripWhereKeyword(tail);
        String head = headOf(sql, tail);
        requireSingleKeyType(operator, credential, parsed.table());
        requireEstimateWithinTolerance(operator, credential, parsed.table(), where, approvedRows, timeoutSeconds);
        return new BulkChangePlan(head, where, parsed.table(), keyColumns, batchRows, timeoutSeconds);
    }

    private static void requireSupportedDbms(DbmsType type) {
        if (!SUPPORTED.contains(type)) {
            throw new WorkbenchRejection(422, "대량 일괄 변경은 아직 " + SUPPORTED.stream().map(Enum::name).reduce((a, b) -> a + "·" + b).orElse("")
                    + "에서만 실행합니다(현재 " + type + ")", null);
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
     * 기본 키가 있어야 한다. 없으면 배치 경계를 정할 수 없어 누락·중복을 막을 수단이 사라진다.
     *
     * <p>복합 키는 사전순 비교로 받는다(#126) — {@code (shop_id, id) > (?, ?)}. 열마다 부등호를 따로 쓰면
     * 구간이 겹치거나 비어 누락·중복이 생기므로, 한 덩이로 비교해 "이 키보다 뒤" 하나의 뜻이 되게 한다.
     * 다만 열이 많아질수록 경계 조회가 인덱스를 타는지 기종별 확인이 필요해 상한을 둔다.
     */
    private static List<String> requirePrimaryKey(DbmsOperator operator, String table) {
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
        if (pk.size() > MAX_KEY_COLUMNS) {
            throw new WorkbenchRejection(422, "기본 키 열이 " + pk.size() + "개입니다(상한 " + MAX_KEY_COLUMNS
                    + ") — 경계 조회가 인덱스를 타는지 확인된 범위까지만 받습니다", null);
        }
        return pk;
    }

    /**
     * 배치 경계로 쓰는 키 열을 SET하는 변경은 거부한다. 경계는 키로 잡고 앞으로만 나아가므로, 키가 바뀌면
     * 뒤로 옮겨 간 행은 이미 지나간 구간으로 가 조용히 빠지고 앞으로 옮겨 간 행은 다시 처리된다. 승인된 문장이
     * 자기 경계를 흔들면 진행 위치({@code lastAppliedKey})가 뜻을 잃는다. DELETE는 값을 바꾸지 않아 해당 없다.
     */
    private static void requireKeysNotModified(String sql, ChangePlan.Kind kind, List<String> keyColumns) {
        if (kind != ChangePlan.Kind.UPDATE) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (String key : keyColumns) {
            keys.add(normalizeColumn(key));
        }
        for (String column : ChangeStatementParser.updateSetColumns(sql)) {
            if (keys.contains(normalizeColumn(column))) {
                throw new WorkbenchRejection(422, "배치 경계로 쓰는 기본 키 열(" + column
                        + ")을 바꾸는 변경은 실행하지 않습니다 — 키가 바뀌면 지나친 구간으로 이동한 행이 조용히 빠지거나 다시 처리됩니다", null);
            }
        }
    }

    /** 인용 부호와 대소문자를 무시하고 열 이름을 비교한다. */
    private static String normalizeColumn(String column) {
        String name = column == null ? "" : column.strip();
        if (name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                name = name.substring(1, name.length() - 1);
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 배치 경계로 쓸 키의 타입이 하나여야 한다(#128). SQL 계열은 열 타입이 고정돼 빈 목록이 와서 그냥 통과한다.
     *
     * <p>MongoDB에서 {@code _id} 타입이 섞이면 {@code $gt}가 타입 경계를 넘지 않아, 경계 조회가 준 마지막 키
     * 뒤의 다른 타입 문서를 하나도 잡지 못한다. 배치는 "더 없다"고 보고 정상 종료하고 그 문서들은 조용히 빠진다.
     */
    private static void requireSingleKeyType(DbmsOperator operator, ConsoleCredential credential, String table) {
        List<String> types;
        try {
            types = operator.bulkKeyTypes(credential, table);
        } catch (RuntimeException e) {
            throw new WorkbenchRejection(422, "배치 키의 타입을 확인하지 못해 실행하지 않습니다: " + e.getMessage(), null);
        }
        if (types != null && types.size() > 1) {
            throw new WorkbenchRejection(422, "배치 키(_id)의 타입이 " + types.size() + "가지입니다("
                    + String.join(", ", types) + ") — 타입이 섞이면 범위 비교가 경계를 넘지 못해"
                    + " 일부 문서가 조용히 빠집니다", null);
        }
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
