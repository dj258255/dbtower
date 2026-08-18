package io.dbtower.operator.model;

/**
 * 복제 상태 통합 뷰 (확장2). 소스: SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV /
 * v$database / replSetGetStatus.
 *
 * <p><b>왜 3-값인가</b>: 예전에는 {@code double lagSeconds} 한 필드가 세 가지 의미를 동시에 실었다 —
 * (1) 실측 지연 초, (2) 이 기종은 지연을 못 잰다, (3) 잴 수 있어야 하는데 지금 못 얻었다.
 * {@code -1}을 센티넬로 쓰고 알림 게이트가 {@code lagSeconds < 0}이면 조용히 스킵했는데, 그 결과:
 *
 * <ul>
 *   <li>Oracle·SQL Server AlwaysOn·MongoDB 레플리카셋은 {@code -1}이 <b>하드코딩</b>이라
 *       복제 지연 알림이 구조적으로 불가능했다</li>
 *   <li>MySQL은 {@code Seconds_Behind_Source}가 NULL일 때 {@code -1}을 냈는데, MySQL에서 그 NULL은
 *       "미상"이 아니라 <b>복제가 끊겼다</b>는 신호다 — 가장 알려야 할 사건에서 알림이 침묵했다</li>
 * </ul>
 *
 * <p>이 저장소는 {@code RestoreVerification}·{@code IndexAdvice}·{@code LatencyPercentile}에는
 * 3-값 상태와 출처 태그를 이미 붙여 놨다. 여기만 빠져 있었다. 같은 규율로 맞춘다 —
 * <b>"못 잰다"와 "지금 못 읽었다"를 절대 같은 값으로 표현하지 않는다.</b>
 *
 * @param role        PRIMARY / REPLICA / STANDALONE / 기종 고유 역할명
 * @param lagSeconds  실측 지연 초. {@code lagSource != MEASURED}면 반드시 null
 * @param lagSource   lagSeconds를 어떻게 얻었는가(또는 왜 못 얻었는가)
 * @param detail      사람이 읽는 부연 — 미측정 사유는 여기에 구체적으로 적는다
 */
public record ReplicationState(String role, Double lagSeconds, LagSource lagSource, String detail) {

    public enum LagSource {
        /** 실측 — lagSeconds가 유효하다. 임계 판정 대상. */
        MEASURED,
        /** 복제 구성이 없다(STANDALONE). 판정 대상이 아니며 알릴 것도 없다. */
        NOT_APPLICABLE,
        /** 이 기종·역할에서 지연을 잴 수단이 없다(구조적). 조용히 넘어가되 화면에는 사유를 표기한다. */
        UNSUPPORTED,
        /**
         * 잴 수 있어야 하는데 지금 못 얻었다 — 복제 단절·권한 부족·조회 실패.
         * <b>이건 침묵이 아니라 신호다.</b> 복제가 구성된 인스턴스에서 지연을 못 읽는 상태가
         * 지속되면 알림이 나가야 한다.
         */
        UNAVAILABLE
    }

    /** 실측 지연. */
    public static ReplicationState measured(String role, double lagSeconds, String detail) {
        return new ReplicationState(role, lagSeconds, LagSource.MEASURED, detail);
    }

    /** 복제 미구성. */
    public static ReplicationState standalone(String detail) {
        return new ReplicationState("STANDALONE", null, LagSource.NOT_APPLICABLE, detail);
    }

    /** 이 기종·역할에서는 지연을 잴 수 없다 — 사유를 반드시 남긴다. */
    public static ReplicationState unsupported(String role, String detail) {
        return new ReplicationState(role, null, LagSource.UNSUPPORTED, detail);
    }

    /** 복제는 구성돼 있는데 지연을 못 읽었다 — 사유를 반드시 남긴다. */
    public static ReplicationState unavailable(String role, String detail) {
        return new ReplicationState(role, null, LagSource.UNAVAILABLE, detail);
    }

    /** 임계 판정 대상인가 — 실측값이 있을 때만. */
    public boolean hasMeasuredLag() {
        return lagSource == LagSource.MEASURED && lagSeconds != null;
    }
}
