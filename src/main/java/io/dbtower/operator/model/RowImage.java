package io.dbtower.operator.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 변경 전후의 행 사본. 값은 정규화한 문자열로 담는다 — 같은 행을 두 번 읽으면 같은 문자열이 나와야 되돌리기 직전의
 * 드리프트 대조가 성립하고, 되돌릴 때는 sqlType으로 타입을 복원해 파라미터로 바인딩한다(문자열 SQL 조립 금지).
 *
 * @param columns    열 이름과 JDBC 타입
 * @param keyColumns 기본 키 열(행 대응의 기준). 비어 있으면 되돌리기 불가
 * @param rows       행마다 columns 순서의 값(null 허용)
 */
public record RowImage(List<ImageColumn> columns, List<String> keyColumns, List<List<String>> rows) {

    /**
     * 문서형 기종(MongoDB)의 되돌리기 원본 열 — 문서 전체의 canonical Extended JSON. 화면용 열은 타입을 잃어(정수와 실수, 날짜와
     * 문자열) 되돌리기에 쓸 수 없어서 따로 둔다. 마스킹을 거치지 않은 원래 값이라 화면 비교에서는 반드시 뺀다.
     */
    public static final String RAW_DOCUMENT_COLUMN = "__raw_document";

    public record ImageColumn(String name, int sqlType, String typeName) {
    }

    public static RowImage empty(List<ImageColumn> columns, List<String> keyColumns) {
        return new RowImage(columns, keyColumns, List.of());
    }

    public int indexOf(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    /** 한 행의 기본 키 값 묶음 — 행 대응의 기준 */
    public List<String> keyOf(List<String> row) {
        List<String> key = new ArrayList<>(keyColumns.size());
        for (String k : keyColumns) {
            key.add(row.get(indexOf(k)));
        }
        return key;
    }
}
