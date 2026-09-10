package io.dbtower.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.operator.SqlCanonical;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 워크벤치 문장 분류기 — 실행 전에 한 문장을 읽기(즉시 실행) / 변경(승인 티켓) / 차단으로 나눈다.
 *
 * <p>분류는 첫 방어선일 뿐 경계가 아니다. 읽기로 분류된 문장도 조회 전용 계정·읽기 전용 트랜잭션·타임아웃·행 상한·
 * 항상 롤백 안에서 실행된다. 그래서 규칙은 "확실한 읽기만 즉시 실행으로 보내고, 모르는 것은 막는다"(허용 목록)로 짠다.
 * 판정은 원문이 아니라 주석·인용을 지운 canonical 사본으로 한다 — 주석 속 세미콜론·키워드로 판정을 속이지 못하게.
 *
 * <p>입력이 {@code {}로 시작하면 MongoDB 명령 JSON으로 본다. 기종을 묻지 않고 문장 모양으로 판단하므로
 * 기종 분기가 생기지 않는다.
 */
public final class StatementClassifier {

    public enum Tier {
        READ,
        NEEDS_APPROVAL,
        BLOCKED
    }

    public record Classification(Tier tier, String kind, String reason) {
    }

    private static final Pattern FIRST_WORD = Pattern.compile("^[\\s(]*([a-zA-Z]+)");

    private static final Set<String> READ_HEADS = Set.of(
            "select", "with", "show", "describe", "desc", "explain", "values", "table");

    private static final Set<String> CHANGE_HEADS = Set.of(
            "insert", "update", "delete", "merge", "replace", "upsert", "create", "alter", "rename", "comment", "drop");

    private static final String TX_CONTROL = "트랜잭션 제어는 읽기 전용 보호를 탈출하는 경로다(COMMIT 뒤에 오는 쓰기)";
    private static final String SESSION_STATE = "세션 상태를 바꾸는 문장은 공유 커넥션을 오염시키고 권한을 바꿀 수 있다";
    private static final String PROCEDURE = "프로시저·동적 SQL은 부작용을 문장만 보고 판정할 수 없다";
    private static final String MAINTENANCE = "운영 작업(락·통계·정리·서버 제어)은 워크벤치 범위 밖이다";

    private static final Map<String, String> BLOCKED_HEADS = Map.ofEntries(
            Map.entry("truncate", "TRUNCATE는 되돌릴 행 사본을 남길 수 없다. WHERE가 있는 DELETE를 변경 요청으로 올려라"),
            Map.entry("grant", "권한 변경은 워크벤치 범위 밖이다"),
            Map.entry("revoke", "권한 변경은 워크벤치 범위 밖이다"),
            Map.entry("set", SESSION_STATE), Map.entry("use", SESSION_STATE), Map.entry("reset", SESSION_STATE),
            Map.entry("discard", SESSION_STATE),
            Map.entry("begin", TX_CONTROL), Map.entry("start", TX_CONTROL), Map.entry("commit", TX_CONTROL),
            Map.entry("rollback", TX_CONTROL), Map.entry("savepoint", TX_CONTROL), Map.entry("release", TX_CONTROL),
            Map.entry("end", TX_CONTROL), Map.entry("abort", TX_CONTROL),
            Map.entry("call", PROCEDURE), Map.entry("exec", PROCEDURE), Map.entry("execute", PROCEDURE),
            Map.entry("do", PROCEDURE), Map.entry("prepare", PROCEDURE), Map.entry("deallocate", PROCEDURE),
            Map.entry("declare", PROCEDURE),
            Map.entry("load", "파일 입출력은 워크벤치 범위 밖이다"), Map.entry("copy", "파일 입출력은 워크벤치 범위 밖이다"),
            Map.entry("lock", MAINTENANCE), Map.entry("unlock", MAINTENANCE), Map.entry("kill", MAINTENANCE),
            Map.entry("shutdown", MAINTENANCE), Map.entry("flush", MAINTENANCE), Map.entry("vacuum", MAINTENANCE),
            Map.entry("analyze", MAINTENANCE), Map.entry("optimize", MAINTENANCE), Map.entry("repair", MAINTENANCE),
            Map.entry("reindex", MAINTENANCE), Map.entry("cluster", MAINTENANCE), Map.entry("checkpoint", MAINTENANCE),
            Map.entry("purge", MAINTENANCE), Map.entry("install", MAINTENANCE), Map.entry("uninstall", MAINTENANCE),
            Map.entry("refresh", MAINTENANCE), Map.entry("listen", MAINTENANCE), Map.entry("notify", MAINTENANCE),
            Map.entry("dbcc", MAINTENANCE), Map.entry("backup", MAINTENANCE), Map.entry("restore", MAINTENANCE));

    /** 계정·데이터베이스 단위 삭제 — 변경 요청으로도 올리지 않는다. */
    private static final Pattern DROP_SCOPE = Pattern.compile(
            "(?i)^[\\s(]*drop\\s+(database|schema|user|role|login|tablespace|owned)\\b");

    /** requireSelect와 같은 집합 — CTE·서브쿼리에 숨은 데이터 변경을 잡는다. */
    private static final Pattern MUTATING = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|truncate|drop|alter|grant|revoke)\\b");

    private static final Pattern LOCKING = Pattern.compile(
            "(?i)\\bfor\\s+(update|share|no\\s+key\\s+update|key\\s+share)\\b|\\block\\s+in\\s+share\\s+mode\\b");

    private static final Pattern FILE_WRITE = Pattern.compile("(?i)\\binto\\s+(outfile|dumpfile)\\b");

    private static final Pattern SELECT_INTO = Pattern.compile("(?i)\\binto\\b");

    /**
     * 조회 모양이지만 부작용이 있는 함수 — 세션 종료, 설정 변경, 파일 입출력, 원격 실행, 락, 시퀀스 증가.
     * 읽기 전용 트랜잭션이 막지 못하는 것(pg_terminate_backend 등)이 섞여 있어 분류기에서 먼저 막는다.
     */
    private static final Pattern SIDE_EFFECT_FUNCTIONS = Pattern.compile("(?i)\\b("
            + "pg_terminate_backend|pg_cancel_backend|set_config|pg_reload_conf|pg_rotate_logfile|pg_switch_wal"
            + "|pg_create_restore_point|pg_promote|lo_import|lo_export|lo_unlink|pg_read_file|pg_read_binary_file"
            + "|pg_ls_dir|pg_stat_file|dblink|dblink_exec|pg_advisory_lock|pg_advisory_xact_lock|pg_try_advisory_lock"
            + "|nextval|setval|load_file|sys_exec|sys_eval|get_lock|release_lock|xp_cmdshell|openrowset"
            + "|opendatasource|openquery|utl_http|utl_file|utl_tcp|utl_smtp|dbms_pipe|dbms_lock|dbms_scheduler"
            + "|dbms_job|dbms_sql)\\s*\\(|\\.nextval\\b");

    private static final Pattern EXPLAIN_PLAN = Pattern.compile("(?i)^[\\s(]*explain\\s+plan\\b");

    private static final Pattern EXPLAIN_ANALYZE = Pattern.compile(
            "(?is)^[\\s(]*explain\\s*(\\([^)]*\\banalyze\\b[^)]*\\)|analyze\\b)");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> MONGO_READ = Set.of("find", "count", "distinct", "aggregate");
    private static final Set<String> MONGO_CHANGE = Set.of(
            "insert", "update", "delete", "findandmodify", "bulkwrite",
            "create", "createindexes", "dropindexes", "collmod", "renamecollection");
    private static final Set<String> MONGO_WRITE_STAGES = Set.of("$out", "$merge");
    private static final Set<String> MONGO_SERVER_JS = Set.of("$where", "$function", "$accumulator");

    private StatementClassifier() {
    }

    public static Classification classify(String statement) {
        if (statement == null || statement.isBlank()) {
            return blocked("EMPTY", "실행할 문장이 비었다");
        }
        String trimmed = statement.strip();
        if (trimmed.startsWith("{")) {
            return classifyMongo(trimmed);
        }
        String canonical = SqlCanonical.canonical(statement);
        if (SqlCanonical.hasStatementSeparator(canonical)) {
            return blocked("MULTI_STATEMENT",
                    "한 번에 한 문장만 실행한다. 이어 붙인 앞 문장이 트랜잭션을 끝내면 뒤 문장은 보호 밖에서 돈다");
        }
        Matcher m = FIRST_WORD.matcher(canonical);
        if (!m.find()) {
            return blocked("UNKNOWN", "문장 유형을 알 수 없다");
        }
        String head = m.group(1).toLowerCase(Locale.ROOT);
        String reason = BLOCKED_HEADS.get(head);
        if (reason != null) {
            return blocked(head.toUpperCase(Locale.ROOT), reason);
        }
        if (DROP_SCOPE.matcher(canonical).find()) {
            return blocked("DROP_SCOPE", "데이터베이스·스키마·계정 단위 삭제는 워크벤치 범위 밖이다");
        }
        if (CHANGE_HEADS.contains(head)) {
            return new Classification(Tier.NEEDS_APPROVAL, head.toUpperCase(Locale.ROOT),
                    "데이터나 구조를 바꾸는 문장이다. 변경 요청(승인 티켓)으로 올려야 실행된다");
        }
        if (READ_HEADS.contains(head)) {
            return classifyRead(head, canonical);
        }
        return blocked("UNKNOWN", "허용 목록에 없는 문장 유형이다(" + head + "). 모르는 문장은 막는다");
    }

    private static Classification classifyRead(String head, String canonical) {
        if ("explain".equals(head)) {
            if (EXPLAIN_PLAN.matcher(canonical).find()) {
                return blocked("EXPLAIN_PLAN",
                        "Oracle EXPLAIN PLAN은 PLAN_TABLE에 쓴다. 실행계획은 콘솔의 실행계획 기능(explain API)으로 본다");
            }
            boolean analyze = EXPLAIN_ANALYZE.matcher(canonical).find();
            if (!analyze) {
                return read("EXPLAIN");
            }
            if (MUTATING.matcher(canonical).find()) {
                return blocked("EXPLAIN_ANALYZE_WRITE",
                        "EXPLAIN ANALYZE는 대상 문장을 실제로 실행한다. 변경 문장의 실행계획은 ANALYZE 없이 본다");
            }
        }
        if (SIDE_EFFECT_FUNCTIONS.matcher(canonical).find()) {
            return blocked("SIDE_EFFECT_FUNCTION",
                    "조회 모양이지만 부작용이 있는 함수다(세션 종료·설정 변경·파일 입출력·락·시퀀스 증가)");
        }
        if (FILE_WRITE.matcher(canonical).find()) {
            return blocked("FILE_WRITE", "서버에 파일을 쓰는 조회다");
        }
        if (LOCKING.matcher(canonical).find()) {
            return blocked("LOCKING_READ", "운영 행에 락을 거는 조회다");
        }
        if (MUTATING.matcher(canonical).find()) {
            return new Classification(Tier.NEEDS_APPROVAL, "with".equals(head) ? "DATA_MODIFYING_CTE" : "MIXED_WRITE",
                    "조회 안에 데이터 변경이 섞여 있다. 변경 요청으로 올려야 실행된다");
        }
        if (("select".equals(head) || "with".equals(head)) && SELECT_INTO.matcher(canonical).find()) {
            return new Classification(Tier.NEEDS_APPROVAL, "SELECT_INTO",
                    "SELECT INTO는 결과로 새 테이블을 만든다(PostgreSQL·SQL Server)");
        }
        return read(head.toUpperCase(Locale.ROOT));
    }

    private static Classification classifyMongo(String json) {
        JsonNode command;
        try {
            command = JSON.readTree(json);
        } catch (Exception e) {
            return blocked("MONGO_INVALID", "JSON 명령을 해석할 수 없다");
        }
        if (command == null || !command.isObject() || command.isEmpty()) {
            return blocked("MONGO_INVALID", "명령 이름이 있는 JSON 객체여야 한다. 예: {\"find\": \"users\"}");
        }
        String name = command.fieldNames().next();
        String lower = name.toLowerCase(Locale.ROOT);
        if (containsKey(command, MONGO_SERVER_JS)) {
            return blocked("MONGO_SERVER_JS", "서버 측 자바스크립트 실행($where·$function·$accumulator)은 막는다");
        }
        if (MONGO_READ.contains(lower)) {
            if ("aggregate".equals(lower) && containsKey(command, MONGO_WRITE_STAGES)) {
                return blocked("MONGO_AGGREGATE_WRITE", "$out·$merge 스테이지는 결과를 컬렉션에 쓴다");
            }
            return read("MONGO_" + lower.toUpperCase(Locale.ROOT));
        }
        if (MONGO_CHANGE.contains(lower)) {
            return new Classification(Tier.NEEDS_APPROVAL, "MONGO_" + lower.toUpperCase(Locale.ROOT),
                    "데이터나 구조를 바꾸는 명령이다. 변경 요청(승인 티켓)으로 올려야 실행된다");
        }
        return blocked("MONGO_UNKNOWN", "허용 목록에 없는 명령이다(" + name + "). 모르는 명령은 막는다");
    }

    /** JSON 트리 어디에든 해당 키가 있는지 — 파이프라인 스테이지·필터 연산자가 중첩돼 있어서 전체를 훑는다. */
    private static boolean containsKey(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (keys.contains(field.getKey()) || containsKey(field.getValue(), keys)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsKey(child, keys)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Classification read(String kind) {
        return new Classification(Tier.READ, kind, "읽기 문장이다. 조회 전용 계정·읽기 전용 트랜잭션 안에서 실행된다");
    }

    private static Classification blocked(String kind, String reason) {
        return new Classification(Tier.BLOCKED, kind, reason);
    }
}
