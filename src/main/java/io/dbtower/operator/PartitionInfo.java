package io.dbtower.operator;

/**
 * 파티션 하나의 조회 결과 (D5 파티션 조회) — "이 테이블이 어떻게 쪼개져 있고, 각 조각이 얼마나 큰가"를
 * 이기종 5기종에서 하나의 모델로 본다. <b>조회만</b> 한다 — 파티션 생성·삭제·병합 같은 자동 관리는
 * 이 기능의 범위 밖이고(대상 DB를 바꾸는 순간 다른 제품이 된다), 여기서는 카탈로그를 읽기만 한다.
 *
 * 같은 "파티션"이라도 기종마다 원자료의 이름·의미가 다르다. 값 자체보다 그 값이 어디서 왔는지가
 * 중요하므로 각 필드의 기종별 의미를 주석으로 못 박는다:
 *
 * <ul>
 *   <li>MySQL      — information_schema.PARTITIONS. method=PARTITION_METHOD(RANGE/LIST/HASH/KEY),
 *       expression=PARTITION_EXPRESSION, boundary=PARTITION_DESCRIPTION(예: VALUES LESS THAN (2025)),
 *       rowCount=TABLE_ROWS(InnoDB 통계 추정치), sizeBytes=DATA_LENGTH.</li>
 *   <li>PostgreSQL — 선언적 파티셔닝(10+). pg_partitioned_table+pg_inherits+pg_class. method=partstrat(r/l/h),
 *       expression=pg_get_partkeydef(부모), boundary=pg_get_expr(relpartbound)(예: FOR VALUES FROM ... TO ...),
 *       rowCount=자식 reltuples(플래너 추정치), sizeBytes=pg_total_relation_size(자식).</li>
 *   <li>Oracle     — user_tab_partitions+user_part_tables. method=partitioning_type, expression=파티션 키 컬럼,
 *       boundary=high_value(LONG), rowCount=num_rows(옵티마이저 통계), sizeBytes=user_segments 합.</li>
 *   <li>SQL Server — sys.partitions+sys.partition_schemes/functions. 파티션은 이름이 아니라 번호라
 *       partitionName은 "partition N", method=RANGE LEFT/RIGHT, expression=파티션 컬럼,
 *       boundary=partition_range_values, rowCount=rows, sizeBytes=allocation_units 합.</li>
 *   <li>MongoDB    — 파티션 개념이 없다(샤딩은 다른 축의 분산이다). 지원하는 척하지 않고 UNSUPPORTED로 정직히 표기.</li>
 * </ul>
 *
 * 파티션이 없는 테이블/DB는 <b>빈 결과</b>다(에러가 아니다). 원자료가 아예 없는 기종만 UNSUPPORTED 안내 행을 낸다.
 *
 * @param tableName           파티션이 속한 (부모) 테이블 이름. UNSUPPORTED 안내 행은 null
 * @param partitionName       파티션 이름(SQL Server는 번호 기반 "partition N")
 * @param partitionMethod     파티션 방식(RANGE/LIST/HASH/KEY 등). UNSUPPORTED 안내 행은 상수 UNSUPPORTED
 * @param partitionExpression 파티션 키 표현식/컬럼. 없으면 null
 * @param boundary            이 파티션의 경계(범위/값 목록). 해시 파티션 등 경계가 없으면 null
 * @param rowCount            파티션 행수(대개 통계 기반 추정치). 미제공이면 null
 * @param sizeBytes           파티션 크기(바이트). 미제공이면 null
 */
public record PartitionInfo(String tableName, String partitionName, String partitionMethod,
                            String partitionExpression, String boundary, Long rowCount, Long sizeBytes) {

    public static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * 파티션 개념 자체가 없는 기종(MongoDB)의 정직한 단일 안내 행. 빈 목록으로 돌려주면
     * "파티션이 없는 것"과 "이 기종은 원래 파티션이 없는 것"을 구분할 수 없어, 명시적 UNSUPPORTED 행으로 알린다.
     * partitionMethod에 UNSUPPORTED 마커를 담고 boundary에 사유를 담는다(나머지 필드는 null).
     */
    public static PartitionInfo unsupported(String reason) {
        return new PartitionInfo(null, null, UNSUPPORTED, null, reason, null, null);
    }
}
