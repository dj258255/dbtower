package io.dbtower.operator.model;

/**
 * 인덱스 하나의 실제 사용 통계 (D6 FinOps) — "이 인덱스가 정말 쓰이고 있나"를 사용 카운터로 본다.
 *
 * 미사용 인덱스는 쓰기마다 갱신 비용만 물고 저장공간을 잡아먹는 대표적 "낭비 후보"다. 하지만 구조만
 * 보는 describeSchema(중복·잉여 판정, D2)로는 "실제로 안 쓰였는지"를 알 수 없다 — 그건 기종별 사용
 * 통계가 있어야 한다. 그래서 값보다 <b>그 값이 어디서 왔는지</b>를 source로 못 박는다(추정을 실측인 척,
 * 미지원을 지원하는 척하지 않는다 — LatencyPercentile과 같은 정직 규약).
 *
 * <ul>
 *   <li>NATIVE      — DB가 유지하는 인덱스 사용 카운터를 직접 읽은 실측.
 *       PostgreSQL pg_stat_user_indexes.idx_scan, MySQL performance_schema.table_io_waits_summary_by_index_usage
 *       .COUNT_STAR(=sys.schema_unused_indexes의 근거), SQL Server sys.dm_db_index_usage_stats(seek/scan/lookup 합),
 *       MongoDB $indexStats.accesses.ops.
 *       <b>공통 한계:</b> 이 카운터는 통계 리셋(서버 재기동 등) 이후 <b>누적</b>이다. 오래 뜬 서버라야
 *       "오랫동안 0회"가 신뢰할 만한 미사용 신호다 — 방금 재기동한 서버의 0회는 미사용이 아니다. 그래서
 *       결과는 삭제 지시가 아니라 검토 "후보"이고, 유니크·PK를 뒷받침하는 인덱스는 제외한다(제약이 필요).</li>
 *   <li>UNSUPPORTED — 인덱스 사용 통계를 신뢰성 있게 얻을 수 없는 기종/환경. Oracle은 인덱스 사용 추적
 *       (MONITORING USAGE / index usage tracking)이 기본 활성도, 권한 보장도 아니라 정직하게 UNSUPPORTED로
 *       표기한다. 지원하는 척하며 빈 목록을 내면 "안 쓰이는 인덱스가 없음"으로 오해되므로, 명시적 안내 행을 낸다.</li>
 * </ul>
 *
 * @param tableName 인덱스가 속한 테이블(컬렉션). UNSUPPORTED 안내 행은 null
 * @param indexName 인덱스 이름. UNSUPPORTED 안내 행은 사유 문장을 담는다
 * @param scanCount 통계 리셋 이후 누적 사용 횟수(0이면 미사용 후보). 없으면 null
 * @param sizeBytes 인덱스 크기(바이트). 기종이 제공하지 않으면 null
 * @param unique    유니크(또는 PK 뒷받침) 인덱스 여부 — 미사용이라도 제약 유지에 필요할 수 있어 후보에서 제외
 * @param source    NATIVE / UNSUPPORTED
 */
public record IndexUsage(String tableName, String indexName, Long scanCount,
                         Long sizeBytes, boolean unique, String source) {

    public static final String NATIVE = "NATIVE";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * 인덱스 사용 통계를 신뢰성 있게 얻을 수 없는 기종의 정직한 단일 안내 행. scanCount/sizeBytes는 null,
     * indexName에 사유를 담는다 — 빈 목록으로 돌려주면 "미사용 인덱스가 없음"과 "이 기종은 원래 판정 불가"를
     * 구분할 수 없어, 명시적 UNSUPPORTED 행으로 알린다.
     */
    public static IndexUsage unsupported(String reason) {
        return new IndexUsage(null, reason, null, null, false, UNSUPPORTED);
    }
}
