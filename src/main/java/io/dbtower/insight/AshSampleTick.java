package io.dbtower.insight;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 샘플 시도 1회의 메타 — 세션이 0건이어도 "쟀다"는 사실을 남긴다.
 *
 * <p>0은 성공과 실패가 같은 모양이다. {@link AshSample}에 행이 없을 때 그것이 "활성 세션이
 * 정말 없었다"인지 "샘플러가 못 돌았다"인지 구분할 수 없다. 이 레코드가 그 차이를 만든다.
 *
 * <p>그리고 AWS RDS Performance Insights가 db.load.avg(보정값)와 db.sampledload.avg(원값)를
 * 따로 노출하듯, "봤다"({@code observedSessions})와 "남겼다"({@code retainedSessions})를
 * 분리해 떨어뜨린 사실을 숨기지 않는다.
 *
 * @param collectMs 샘플링 쿼리 왕복 시간 — A9 원칙("조회 자체가 부하가 되면 안 된다") 검증축
 * @param status    {@link #OK} / {@link #SKIPPED_INFLIGHT} / {@link #ERROR}
 */
public record AshSampleTick(
        long instanceId,
        LocalDateTime sampledAt,
        LocalDateTime ingestedAt,
        UUID samplerRunId,
        long sampleSeq,
        int observedSessions,
        int retainedSessions,
        int droppedSessions,
        double collectMs,
        String status,
        String errorMessage
) {
    public static final String OK = "OK";
    /** 직전 틱이 아직 안 끝나 이번 틱을 버렸다 — 쌓지 않고 떨어뜨리는 백프레셔 정책의 흔적. */
    public static final String SKIPPED_INFLIGHT = "SKIPPED_INFLIGHT";
    public static final String ERROR = "ERROR";
}
