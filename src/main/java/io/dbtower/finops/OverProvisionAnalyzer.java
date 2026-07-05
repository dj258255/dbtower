package io.dbtower.finops;

import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbParameter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 오버프로비저닝 신호 (D6) — 기존 조회(parameters·activeSessions·tableStats)를 조합해 "설정이 워크로드보다
 * 크게 넉넉한가"를 <b>신호</b>로만 낸다. rightsizing 결정은 워크로드·캐시 적중률·피크까지 봐야 하므로 사람이 한다.
 *
 * 두 신호:
 * - 연결 여유: 활성 세션 수가 max_connections를 크게 밑돈다. 활성 세션은 유휴 연결을 제외한 <b>하한</b>이라
 *   "이보다 많이 쓸 수도 있다"는 점을 근거에 정직하게 밝힌다 — 그래도 상한과 자릿수가 다르면 재검토 신호다.
 * - 메모리 여유: 버퍼/캐시 설정(PG shared_buffers, MySQL innodb_buffer_pool_size)이 데이터 총량을 여러 배
 *   웃돈다. 캐시가 데이터보다 훨씬 크면 그만큼 메모리를 놀리는 신호일 수 있다.
 *
 * 파라미터 의미가 기종마다 갈려(Oracle=processes, SQL Server=user connections, Mongo=별개) 오판을 피하기
 * 위해, 이름·단위가 동일한 PostgreSQL·MySQL만 지원한다(나머지는 UNSUPPORTED로 정직). 절감액은 지어내지 않는다.
 */
@Component
public class OverProvisionAnalyzer implements FinOpsAnalyzer {

    /** 활성 세션이 max_connections의 이 비율 이하일 때 연결 여유 신호(자릿수 차이). */
    private static final double CONNECTION_HEADROOM_RATIO = 0.1;

    /** max_connections가 이 미만이면 애초에 여유를 논할 크기가 아니라 신호 제외. */
    private static final int MIN_MAX_CONNECTIONS = 100;

    /** 버퍼/캐시가 데이터 총량의 이 배를 넘으면 메모리 여유 신호. */
    private static final double MEMORY_HEADROOM_FACTOR = 4.0;

    /** 활성 세션·크기 조회 상한 */
    private static final int LIMIT = 500;

    @Override
    public String id() {
        return "over-provision";
    }

    @Override
    public String title() {
        return "오버프로비저닝 신호";
    }

    @Override
    public boolean supports(DbmsType type) {
        // max_connections·버퍼 파라미터의 이름·단위가 동일한 기종만 — 오판 방지
        return type == DbmsType.POSTGRESQL || type == DbmsType.MYSQL;
    }

    @Override
    public List<WasteCandidate> analyze(DatabaseInstance instance, DbmsOperator operator) {
        List<WasteCandidate> candidates = new ArrayList<>();
        Map<String, DbParameter> params = new HashMap<>();
        for (DbParameter p : operator.parameters()) {
            params.put(p.name().toLowerCase(), p);
        }

        connectionHeadroom(operator, params).ifPresent(candidates::add);
        memoryHeadroom(instance, operator, params).ifPresent(candidates::add);
        return candidates;
    }

    /** 활성 세션 수 대비 max_connections 여유 신호(활성은 유휴 제외 하한임을 명시). */
    private java.util.Optional<WasteCandidate> connectionHeadroom(
            DbmsOperator operator, Map<String, DbParameter> params) {
        Long maxConn = asLong(params.get("max_connections"));
        if (maxConn == null || maxConn < MIN_MAX_CONNECTIONS) {
            return java.util.Optional.empty();
        }
        int active = operator.activeSessions(LIMIT).size();
        if (active > maxConn * CONNECTION_HEADROOM_RATIO) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new WasteCandidate(
                WasteKind.CONNECTION_HEADROOM, Severity.INFO, "max_connections=" + maxConn,
                "현재 활성 세션 " + active + "개가 max_connections " + maxConn + "를 크게 밑돈다"
                        + "(활성 세션은 유휴 연결을 제외한 하한 — 실제 총 연결은 이보다 많을 수 있다).",
                "피크 시간대 총 연결 수까지 확인한 뒤에도 여유가 크다면, 커넥션 상한·풀 사이즈 재검토 신호다. 실행은 사람이 한다."));
    }

    /** 버퍼/캐시 설정 대비 데이터 총량 여유 신호. */
    private java.util.Optional<WasteCandidate> memoryHeadroom(
            DatabaseInstance instance, DbmsOperator operator, Map<String, DbParameter> params) {
        DbParameter cacheParam = instance.getType() == DbmsType.POSTGRESQL
                ? params.get("shared_buffers")
                : params.get("innodb_buffer_pool_size");
        Long cacheBytes = toBytes(cacheParam);
        if (cacheBytes == null || cacheBytes <= 0) {
            return java.util.Optional.empty();
        }
        long dataTotal = 0;
        for (TableStat t : operator.tableStats(LIMIT)) {
            dataTotal += t.dataBytes() + t.indexBytes();
        }
        if (dataTotal <= 0 || cacheBytes <= dataTotal * MEMORY_HEADROOM_FACTOR) {
            return java.util.Optional.empty();
        }
        double ratio = (double) cacheBytes / dataTotal;
        String name = cacheParam.name();
        return java.util.Optional.of(new WasteCandidate(
                WasteKind.MEMORY_HEADROOM, Severity.INFO, name,
                name + " " + FinOpsFormat.bytes(cacheBytes) + "가 데이터 총량 "
                        + FinOpsFormat.bytes(dataTotal) + "의 약 " + String.format("%.1f", ratio) + "배다.",
                "캐시가 데이터보다 훨씬 크면 메모리를 놀리는 신호일 수 있다. 캐시 적중률·성장 추이를 함께 보고 rightsizing은 사람이 판단한다."));
    }

    /** 파라미터 값을 정수로(순수 숫자만). 실패·부재면 null. */
    private static Long asLong(DbParameter p) {
        if (p == null || p.value() == null) {
            return null;
        }
        try {
            return Long.parseLong(p.value().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 메모리 파라미터를 바이트로 환산한다. PostgreSQL pg_settings는 값(블록 수)+단위(예: "8kB")로 주고,
     * MySQL innodb_buffer_pool_size는 단위 없이 바이트 값이다. 단위가 있으면 값×단위바이트, 없으면 값=바이트.
     */
    private static Long toBytes(DbParameter p) {
        Long value = asLong(p);
        if (value == null) {
            return null;
        }
        if (p.unit() == null || p.unit().isBlank()) {
            return value; // MySQL: 이미 바이트
        }
        Long unitBytes = unitToBytes(p.unit().trim());
        return unitBytes == null ? null : value * unitBytes;
    }

    /** pg_settings 단위 문자열("8kB"/"kB"/"MB"/"16MB"/"B")을 바이트 배수로. 알 수 없으면 null. */
    private static Long unitToBytes(String unit) {
        int i = 0;
        while (i < unit.length() && Character.isDigit(unit.charAt(i))) {
            i++;
        }
        long factor = i == 0 ? 1 : Long.parseLong(unit.substring(0, i));
        String suffix = unit.substring(i);
        long base = switch (suffix) {
            case "B" -> 1L;
            case "kB" -> 1024L;
            case "MB" -> 1024L * 1024;
            case "GB" -> 1024L * 1024 * 1024;
            case "TB" -> 1024L * 1024 * 1024 * 1024;
            default -> -1L;
        };
        return base < 0 ? null : factor * base;
    }
}
