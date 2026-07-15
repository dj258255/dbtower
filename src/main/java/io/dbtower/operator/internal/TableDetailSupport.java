package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * tableDetail 공통 유틸 (심화 아크 3) — 식별자 검증(주입 방어)과 재구성 DDL 조립.
 *
 * SHOW CREATE TABLE / DBMS_METADATA처럼 식별자를 파라미터 바인딩할 수 없는 자리에 테이블명이
 * 들어가므로, 문자 집합을 강하게 제한해 임의 문자열이 SQL에 이어지는 것을 원천 차단한다
 * (renderCommand 주입 방어와 같은 원칙).
 */
final class TableDetailSupport {

    /** 일반적인 테이블 식별자만 허용 — 공백·따옴표·세미콜론 등은 전부 거부(주입 방어) */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$#]{1,128}$");

    private TableDetailSupport() {
    }

    static String requireIdentifier(String table) {
        if (table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new OperatorException("허용되지 않는 테이블 식별자입니다: " + table);
        }
        return table;
    }

    /** 재구성 DDL의 컬럼 한 줄 재료 */
    record ColumnDef(String name, String type, boolean nullable, String defaultValue) {
    }

    /**
     * 카탈로그에서 읽은 컬럼·PK·인덱스로 근사 CREATE TABLE을 조립한다 — SHOW CREATE TABLE이 없는
     * 기종(PostgreSQL·MSSQL)용. 제약조건(FK/CHECK)·트리거·파티션 정의는 담지 않으며, 호출자는
     * ddlSource=RECONSTRUCTED로 근사임을 표기해야 한다(원문 위장 금지).
     */
    static String reconstructDdl(String table, List<ColumnDef> columns, List<String> pkColumns,
                                 List<String> indexDefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(table).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef c = columns.get(i);
            sb.append("  ").append(c.name()).append(' ').append(c.type());
            if (!c.nullable()) {
                sb.append(" NOT NULL");
            }
            if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            if (i < columns.size() - 1 || !pkColumns.isEmpty()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (!pkColumns.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(String.join(", ", pkColumns)).append(")\n");
        }
        sb.append(")");
        for (String def : indexDefs) {
            if (def != null && !def.isBlank()) {
                sb.append(";\n").append(def);
            }
        }
        return sb.toString();
    }
}
