package io.dbtower.operator.model;

/** 복제 상태 통합 뷰 (확장2). 소스: SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV */
public record ReplicationState(String role, double lagSeconds, String detail) {
}
