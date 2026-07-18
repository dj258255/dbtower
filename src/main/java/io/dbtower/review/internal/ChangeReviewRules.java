package io.dbtower.review.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

/**
 * 스키마 변경 SQL 위험 판정 (운영 병목 아크 B2, R2). RuleBasedAnalyzer가 실행계획을 규칙으로
 * 읽듯, 이쪽은 변경 SQL을 규칙으로 읽어 락 위험·WHERE 없는 대량 변경·DROP 위험을 지적한다.
 *
 * 파싱은 JSqlParser(구문 트리)로 한다 — 다중 문장·문자열 안 세미콜론·달러 인용을 정규식처럼
 * 오인하지 않으므로, 정상 SQL이면 parseLimited가 꺼진다. 파서가 못 다루는 방언(Oracle MODIFY
 * 일부·벤더 힌트)이면 정규식으로 내려가되 parseLimited=true로 "근사치"임을 정직하게 드러낸다.
 *
 * 판정은 조언이지 차단이 아니다 — 강제력은 조직 프로세스(승인 워크플로)의 몫이다. 여기서 뽑은
 * columnOps(추가·삭제 대상 컬럼)는 서비스가 실제 스키마와 대조해 "이미 있는 컬럼 추가" 같은
 * 판정을 얹는 재료로 쓴다(이 클래스는 SQL만 본다).
 */
public class ChangeReviewRules {

    /** 규칙 스냅샷 버전 — 규칙이 늘면 올린다(과거 판정과 비교 가능하게 저장). JSqlParser 도입·컬럼 대조로 2. */
    public static final int VERSION = 2;

    /** 대테이블 판정 임계 — 이보다 큰 테이블의 락 유발 변경은 온라인 DDL 권고(행수는 tableDetail로 확인). */
    private static final long BIG_TABLE_ROWS = 1_000_000;

    // --- 정규식 폴백 전용 (파서가 SQL을 못 다룰 때만 쓴다) ---
    private static final Pattern STMT_SPLIT = Pattern.compile(";");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)\\balter\\s+table\\s+(?:only\\s+)?[\"`]?([a-zA-Z0-9_$.]+)[\"`]?");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)\\bdrop\\s+table\\b");
    private static final Pattern DROP_COLUMN = Pattern.compile("(?is)\\bdrop\\s+column\\b");
    private static final Pattern TRUNCATE = Pattern.compile("(?is)\\btruncate\\b");
    private static final Pattern UPDATE_STMT = Pattern.compile("(?is)\\bupdate\\s+[\"`]?([a-zA-Z0-9_$.]+)");
    private static final Pattern DELETE_STMT = Pattern.compile("(?is)\\bdelete\\s+from\\s+[\"`]?([a-zA-Z0-9_$.]+)");
    private static final Pattern HAS_WHERE = Pattern.compile("(?is)\\bwhere\\b");
    private static final Pattern ADD_NOT_NULL = Pattern.compile("(?is)\\badd\\s+column\\b.*\\bnot\\s+null\\b(?!.*\\bdefault\\b)");

    /** ALTER가 건드리는 컬럼 연산 — 서비스가 실제 스키마와 대조하는 재료(add=추가, false=삭제). */
    public record ColumnOp(String table, String column, boolean add) {
    }

    /** 판정 결과 — 지적 목록, 참조 대테이블(락 위험 확인용), 파싱 한계 여부, 컬럼 연산 목록. */
    public record Verdict(List<String> findings, Optional<String> alterTable, boolean parseLimited,
                          List<ColumnOp> columnOps) {
    }

    /**
     * SQL을 규칙으로 읽는다. 먼저 JSqlParser로 구문 트리를 세워 정확히 판정하고, 파서가 실패하면
     * 정규식으로 내려간다(그 경우만 parseLimited=true).
     */
    public Verdict evaluate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Verdict(List.of("SQL이 비어 있습니다."), Optional.empty(), false, List.of());
        }
        try {
            return evaluateParsed(sql);
        } catch (JSQLParserException | RuntimeException e) {
            // 파서가 못 다루는 방언·구문 — 정규식 근사로 내려가고 한계를 표기한다
            return evaluateByRegex(sql);
        }
    }

    /** 구문 트리 기반 판정 — 정상 파싱이면 parseLimited=false. */
    private Verdict evaluateParsed(String sql) throws JSQLParserException {
        Statements parsed = CCJSqlParserUtil.parseStatements(stripComments(sql));
        List<Statement> statements = parsed.getStatements();

        List<String> findings = new ArrayList<>();
        List<ColumnOp> columnOps = new ArrayList<>();
        Optional<String> alterTable = Optional.empty();

        for (Statement stmt : statements) {
            if (stmt instanceof Alter alter) {
                String table = alter.getTable().getName();
                if (alterTable.isEmpty()) {
                    alterTable = Optional.of(table);
                }
                findings.add("R-LOCK: ALTER TABLE " + table + " — 대상이 크면 테이블 락으로 쓰기가 막힐 수 있습니다. "
                        + "행수를 확인해 큰 테이블이면 온라인 DDL(MySQL은 gh-ost)을 검토하세요.");
                inspectAlter(alter, table, findings, columnOps);
            } else if (stmt instanceof Drop drop && "TABLE".equalsIgnoreCase(drop.getType())) {
                findings.add("R-DROP: DROP TABLE — 되돌릴 수 없는 데이터 손실입니다. 백업·의존 객체(뷰·FK)를 먼저 확인하세요.");
            } else if (stmt instanceof Truncate) {
                findings.add("R-TRUNCATE: TRUNCATE — 전체 행 삭제이고 대개 롤백/트리거 없이 즉시 반영됩니다.");
            } else if (stmt instanceof Update upd && upd.getWhere() == null) {
                findings.add("R-NOWHERE: WHERE 없는 UPDATE " + upd.getTable().getName()
                        + " — 전 행을 갱신합니다. 의도한 것이 맞는지 확인하세요.");
            } else if (stmt instanceof Delete del && del.getWhere() == null) {
                findings.add("R-NOWHERE: WHERE 없는 DELETE " + del.getTable().getName()
                        + " — 전 행을 삭제합니다. TRUNCATE와 실질 동일합니다.");
            }
        }

        if (findings.isEmpty()) {
            findings.add("규칙에 걸린 위험 신호가 없습니다. (규칙은 대표 위험만 봅니다 — 최종 판단은 사람이)");
        }
        return new Verdict(findings, alterTable, false, columnOps);
    }

    /** ALTER의 각 변경절을 본다 — ADD/DROP 컬럼을 columnOps에 담고, DEFAULT 없는 NOT NULL을 지적한다. */
    private void inspectAlter(Alter alter, String table, List<String> findings, List<ColumnOp> columnOps) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions == null) {
            return;
        }
        for (AlterExpression ae : expressions) {
            AlterOperation op = ae.getOperation();
            if (op == AlterOperation.ADD && ae.getColDataTypeList() != null) {
                for (ColumnDefinition col : ae.getColDataTypeList()) {
                    columnOps.add(new ColumnOp(table, unquote(col.getColumnName()), true));
                    if (isNotNullWithoutDefault(col.getColumnSpecs())) {
                        findings.add("R-NOTNULL: DEFAULT 없는 NOT NULL 컬럼 추가(" + col.getColumnName() + ") — "
                                + "전 행 재기록으로 락·시간이 길어질 수 있습니다. "
                                + "DEFAULT를 주거나 단계적(nullable 추가 → 백필 → NOT NULL)으로 나누세요.");
                    }
                }
            } else if (op == AlterOperation.DROP && ae.getColumnName() != null) {
                columnOps.add(new ColumnOp(table, unquote(ae.getColumnName()), false));
                findings.add("R-DROPCOL: DROP COLUMN " + ae.getColumnName()
                        + " — 애플리케이션이 그 컬럼을 아직 읽으면 즉시 장애입니다. 코드 배포 순서를 확인하세요.");
            }
        }
    }

    /** 컬럼 스펙 토큰(예: ["NOT","NULL"])에서 NOT NULL이면서 DEFAULT가 없는지 본다. */
    private static boolean isNotNullWithoutDefault(List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", specs).toUpperCase(Locale.ROOT);
        return joined.contains("NOT NULL") && !joined.contains("DEFAULT");
    }

    /**
     * 컬럼 추가/삭제가 실제 스키마와 어긋나는지 한 줄로 표현한다. 존재 여부는 서비스가 describeSchema로
     * 확인해 넘긴다(이 클래스는 SQL만 본다) — "이미 있는 컬럼 추가"·"없는 컬럼 삭제"는 배포 시 에러/무효.
     */
    public Optional<String> schemaMismatchLine(ColumnOp op, boolean columnExists) {
        if (op.add() && columnExists) {
            return Optional.of("R-COL-EXISTS: " + op.table() + "." + op.column()
                    + " 컬럼이 이미 존재합니다 — ADD COLUMN이 에러이거나 무효입니다. 스키마 현황을 확인하세요.");
        }
        if (!op.add() && !columnExists) {
            return Optional.of("R-COL-MISSING: " + op.table() + "." + op.column()
                    + " 컬럼이 존재하지 않습니다 — DROP COLUMN이 에러입니다. 이미 삭제됐는지 확인하세요.");
        }
        return Optional.empty();
    }

    /**
     * 대테이블 락 위험 확정 — 참조 테이블의 실제 행수를 받아, 임계 초과면 findings에 온라인 DDL 권고를
     * 강화한다. 행수 확인은 서비스가 tableDetail로 하고 여기 넘긴다(이 클래스는 SQL만 본다).
     */
    public String lockRiskLine(String table, long rowCount) {
        if (rowCount >= BIG_TABLE_ROWS) {
            return "R-LOCK-CONFIRM: " + table + " 약 " + String.format(Locale.ROOT, "%,d", rowCount)
                    + "행 — 큰 테이블입니다. 이 ALTER는 온라인 DDL 없이는 쓰기를 오래 막을 수 있습니다.";
        }
        return "R-LOCK-OK: " + table + " 약 " + String.format(Locale.ROOT, "%,d", rowCount)
                + "행 — 임계(100만) 미만이라 락 시간은 짧을 가능성이 높습니다(그래도 트래픽 시간대는 피하세요).";
    }

    /** 정규식 폴백 — 파서가 SQL을 못 다룰 때만. 컬럼 대조는 불가(구조 정보 없음)라 columnOps는 비운다. */
    private Verdict evaluateByRegex(String sql) {
        String normalized = stripComments(sql);
        String[] statements = STMT_SPLIT.split(normalized);

        List<String> findings = new ArrayList<>();
        Optional<String> alterTable = Optional.empty();

        for (String raw : statements) {
            String stmt = raw.strip();
            if (stmt.isEmpty()) {
                continue;
            }
            String lower = stmt.toLowerCase(Locale.ROOT);

            Matcher alter = ALTER_TABLE.matcher(stmt);
            if (alter.find()) {
                String table = alter.group(1);
                if (alterTable.isEmpty()) {
                    alterTable = Optional.of(table);
                }
                findings.add("R-LOCK: ALTER TABLE " + table + " — 대상이 크면 테이블 락으로 쓰기가 막힐 수 있습니다. "
                        + "행수를 확인해 큰 테이블이면 온라인 DDL(MySQL은 gh-ost)을 검토하세요.");
                if (ADD_NOT_NULL.matcher(stmt).find()) {
                    findings.add("R-NOTNULL: DEFAULT 없는 NOT NULL 컬럼 추가 — 전 행 재기록으로 락·시간이 길어질 수 있습니다. "
                            + "DEFAULT를 주거나 단계적(nullable 추가 → 백필 → NOT NULL)으로 나누세요.");
                }
            }
            if (DROP_TABLE.matcher(stmt).find()) {
                findings.add("R-DROP: DROP TABLE — 되돌릴 수 없는 데이터 손실입니다. 백업·의존 객체(뷰·FK)를 먼저 확인하세요.");
            }
            if (DROP_COLUMN.matcher(stmt).find()) {
                findings.add("R-DROPCOL: DROP COLUMN — 애플리케이션이 그 컬럼을 아직 읽으면 즉시 장애입니다. 코드 배포 순서를 확인하세요.");
            }
            if (TRUNCATE.matcher(stmt).find()) {
                findings.add("R-TRUNCATE: TRUNCATE — 전체 행 삭제이고 대개 롤백/트리거 없이 즉시 반영됩니다.");
            }
            Matcher upd = UPDATE_STMT.matcher(stmt);
            if (upd.find() && !HAS_WHERE.matcher(lower).find()) {
                findings.add("R-NOWHERE: WHERE 없는 UPDATE " + upd.group(1) + " — 전 행을 갱신합니다. 의도한 것이 맞는지 확인하세요.");
            }
            Matcher del = DELETE_STMT.matcher(stmt);
            if (del.find() && !HAS_WHERE.matcher(lower).find()) {
                findings.add("R-NOWHERE: WHERE 없는 DELETE " + del.group(1) + " — 전 행을 삭제합니다. TRUNCATE와 실질 동일합니다.");
            }
        }

        if (findings.isEmpty()) {
            findings.add("규칙에 걸린 위험 신호가 없습니다. (규칙은 대표 위험만 봅니다 — 최종 판단은 사람이)");
        }
        // 파서가 실패해 정규식으로 내려온 경로 — 항상 근사치임을 표기
        return new Verdict(findings, alterTable, true, List.of());
    }

    /** 줄·블록 주석 제거 — 주석 안 세미콜론·키워드가 오판을 만들지 않게. */
    private static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)--.*$", " ");
    }

    /** 따옴표·백틱으로 감싼 식별자를 벗긴다(스키마 대조 시 비교키를 맞추려고). */
    private static String unquote(String identifier) {
        if (identifier == null || identifier.length() < 2) {
            return identifier;
        }
        char first = identifier.charAt(0);
        char last = identifier.charAt(identifier.length() - 1);
        if ((first == '"' || first == '`' || first == '[') && (last == '"' || last == '`' || last == ']')) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }
}
