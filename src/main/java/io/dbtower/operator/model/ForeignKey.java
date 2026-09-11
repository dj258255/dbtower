package io.dbtower.operator.model;

import java.util.List;

/**
 * 외래키 하나 (VERIFICATION 151·153절). columns[i]가 refColumns[i]를 가리킨다(복합 키는 키 순서).
 *
 * 테이블 상세({@link TableDetail})와 구조 스냅샷({@link TableSchema})이 같은 타입을 쓴다 —
 * 상세는 가리키는 것과 가리켜지는 것을 모두 보여 주고, 구조 스냅샷은 제약을 가진 쪽만 담아 두 시점을 비교한다.
 * 다른 스키마의 테이블은 schema.table로 적는다(같은 이름이 현재 스키마에 있다고 오해하지 않게).
 *
 * @param table    제약을 가진 쪽(가리키는 키면 이 테이블 자신)
 * @param onDelete 참조되는 행을 지울 때의 동작(NO ACTION·RESTRICT·CASCADE·SET NULL·SET DEFAULT). 기종이 주지 않으면 null
 * @param onUpdate 참조되는 키를 바꿀 때의 동작. Oracle은 개념이 없어 null
 */
public record ForeignKey(String name, String table, List<String> columns,
                         String refTable, List<String> refColumns, String onDelete, String onUpdate) {
}
