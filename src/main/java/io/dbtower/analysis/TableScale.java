package io.dbtower.analysis;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 프롬프트에 붙일 <b>대상 테이블의 전체 행수</b>.
 *
 * <p>왜 필요한가: 계획은 조건이 몇 행을 뽑을지를 추정치로 들고 있지만({@code Plan Rows},
 * {@code rows_examined_per_scan}), 그 수가 전체의 몇 %인지는 들고 있지 않다. 선택도는 비율이라
 * 분모가 없으면 판단할 수 없다. {@code created_at >= '2000-01-01'}이 전 기간 조건인지 아닌지가
 * 대표적이다 — 값을 가리면 "얼마나 오래된 날짜인가"만 남는데, 데이터가 언제부터 쌓였는지를 모르면
 * 그것만으로는 전 행인지 일부인지 가를 수 없다(M4·L8 사례, docs/experiments/ai-masking-levels.md).
 *
 * <p>그래서 가림 수준을 더 정교하게 만드는 대신 분모를 준다. 행수는 값이 아니라 규모라 가릴 필요가 없다.
 *
 * <p>비용은 스키마 요약 한 번과 테이블 상세 몇 번이다. 상한을 두고, 실패하면 조용히 비운다 —
 * 이 줄이 없어도 진단은 돌아야 한다.
 */
public final class TableScale {

    private static final Logger log = LoggerFactory.getLogger(TableScale.class);

    /** SQL에서 낱말을 뽑아 스키마의 테이블 이름과 맞춘다 — 파서 없이도 대상만 고른다. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /** 한 프롬프트에 붙일 테이블 수 상한 — 조인이 길어도 진단은 큰 쪽 몇 개로 갈린다. */
    private static final int MAX_TABLES = 3;

    private TableScale() {
    }

    /**
     * {@code "대상 테이블 행수: exp_mask_orders 약 200,000행"} 한 줄. 붙일 것이 없으면 빈 문자열이다.
     */
    public static String describe(DbmsOperator operator, String sql) {
        if (operator == null || sql == null || sql.isBlank()) {
            return "";
        }
        try {
            SchemaSnapshot schema = operator.describeSchema();
            if (schema == null || schema.tables() == null || schema.tables().isEmpty()) {
                return "";
            }
            Set<String> known = schema.tables().stream()
                    .map(t -> t.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Set<String> wanted = new LinkedHashSet<>();
            WORD.matcher(sql).results()
                    .map(r -> r.group().toLowerCase(Locale.ROOT))
                    .filter(known::contains)
                    .forEach(wanted::add);
            List<String> parts = new ArrayList<>();
            for (String name : wanted.stream().limit(MAX_TABLES).toList()) {
                TableDetail detail = operator.tableDetail(name);
                if (detail != null && detail.rowCount() > 0) {
                    parts.add("%s 약 %,d행".formatted(name, detail.rowCount()));
                }
            }
            return parts.isEmpty() ? "" : "대상 테이블 행수: " + String.join(", ", parts);
        } catch (Exception e) {
            // 카탈로그 조회 권한이 없거나 기종이 지원하지 않을 수 있다 — 진단을 막지 않는다
            log.debug("대상 테이블 행수를 붙이지 못했다: {}", e.toString());
            return "";
        }
    }
}
