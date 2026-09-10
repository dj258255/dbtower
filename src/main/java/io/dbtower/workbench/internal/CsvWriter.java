package io.dbtower.workbench.internal;

import io.dbtower.operator.model.ResultColumn;

import java.util.List;

/**
 * RFC 4180 CSV. 스프레드시트 수식 주입을 막는다: {@code =}, {@code +}, {@code -}, {@code @}로 시작하는 셀은
 * 엑셀이 수식으로 실행하므로 앞에 작은따옴표를 붙여 텍스트로 고정한다(DB 값은 신뢰할 수 없는 입력이다).
 */
final class CsvWriter {

    private CsvWriter() {
    }

    static String write(List<ResultColumn> columns, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        // 엑셀이 한글을 깨지 않게 BOM을 붙인다
        sb.append('﻿');
        appendRow(sb, columns.stream().map(c -> (Object) c.name()).toList());
        for (List<Object> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !(value instanceof Number)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
