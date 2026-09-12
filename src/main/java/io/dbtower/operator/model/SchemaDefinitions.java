package io.dbtower.operator.model;

import java.util.List;

// 빈 목록과 권한 부족을 구별해야 구조 비교가 미확보를 삭제로 오인하지 않는다.
public record SchemaDefinitions(Status status, List<SchemaDefinition> definitions, String note) {
    public enum Status { AVAILABLE, UNAVAILABLE, UNSUPPORTED }

    public SchemaDefinitions {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        if (status == null) {
            status = Status.UNAVAILABLE;
        }
    }

    public static SchemaDefinitions unavailable(String note) {
        return new SchemaDefinitions(Status.UNAVAILABLE, List.of(), note);
    }

    public static SchemaDefinitions unsupported() {
        return new SchemaDefinitions(Status.UNSUPPORTED, List.of(), "UNSUPPORTED");
    }
}
