package io.dbtower.registry;

/** 플랫폼이 관리하는 DBMS 기종. 기종마다 통계 소스·구문이 달라 Operator 구현이 갈린다. */
public enum DbmsType {
    MYSQL,
    POSTGRESQL,
    MSSQL,
    MONGODB,
    ORACLE
}
