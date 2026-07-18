package io.dbtower.review.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스키마 변경 SQL 위험 판정 (운영 병목 아크 B2, R2). RuleBasedAnalyzer가 실행계획을 규칙으로
 * 읽듯, 이쪽은 변경 SQL을 규칙으로 읽어 락 위험·WHERE 없는 대량 변경·DROP 위험을 지적한다.
 *
 * 판정은 조언이지 차단이 아니다 — 강제력은 조직 프로세스(승인 워크플로)의 몫이다. SQL 파싱은
 * 완전 파서가 아니라 정규식 수준이라(서브쿼리·다중 문장·주석 안 리터럴 등 한계), 그 한계를
 * parseLimited로 정직하게 드러낸다(ReferencedTables와 같은 원칙).
 */
public class ChangeReviewRules {

    /** 규칙 스냅샷 버전 — 규칙이 늘면 올린다(과거 판정과 비교 가능하게 저장). */
    public static final int VERSION = 1;

    /** 대테이블 판정 임계 — 이보다 큰 테이블의 락 유발 변경은 온라인 DDL 권고(행수는 tableDetail로 확인). */
    private static final long BIG_TABLE_ROWS = 1_000_000;

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

    /** 판정 결과 — 지적 목록, 참조 대테이블(락 위험 확인용), 파싱 한계 여부. */
    public record Verdict(List<String> findings, Optional<String> alterTable, boolean parseLimited) {
    }

    /**
     * SQL을 규칙으로 읽는다. 다중 문장이면 각각 판정하고, 파싱이 못 다루는 구조(서브쿼리 중첩·
     * 문자열 안 세미콜론 등)는 parseLimited로 표기한다.
     */
    public Verdict evaluate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Verdict(List.of("SQL이 비어 있습니다."), Optional.empty(), false);
        }
        String normalized = stripComments(sql);
        String[] statements = STMT_SPLIT.split(normalized);
        boolean multi = statements.length > 1;

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
        // 파싱 한계: 다중 문장·문자열 리터럴 안 세미콜론·중첩은 정규식이 못 가른다 → 정직 표기
        boolean parseLimited = multi || normalized.contains("$$") || countQuotes(normalized) % 2 != 0;
        return new Verdict(findings, alterTable, parseLimited);
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

    /** 줄·블록 주석 제거 — 주석 안 세미콜론·키워드가 오판을 만들지 않게. */
    private static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)--.*$", " ");
    }

    private static long countQuotes(String s) {
        return s.chars().filter(c -> c == '\'').count();
    }
}
