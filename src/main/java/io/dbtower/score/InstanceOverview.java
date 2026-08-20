package io.dbtower.score;

import io.dbtower.registry.DbmsType;

/**
 * 대상별 운영 종합 뷰 (DBRE) — "이 DB가 무엇인지"(정체)와 "지금 어떤 상태인지"(건강·복제·백업)를
 * 하나의 대상 단위에 귀속시킨다. 관제 데이터가 모듈별로 흩어져 있던 것을 인스턴스 하나로 모은다.
 *
 * <p>DBTower는 관제 plane이라 이 뷰는 <b>읽기 전용 집계</b>다 — 조치(승격·설정 변경 등)를 여기서
 * 실행하지 않는다. 각 조각은 이미 있는 공개 서비스(score/replication/backup)에서 그대로 가져온다.
 */
public record InstanceOverview(
        long id,
        String name,
        DbmsType type,
        String host,
        int port,
        String environment,
        String cluster,
        String teamLabel,
        int healthScore,
        String grade,
        boolean down,
        Replication replication,
        Backup backup) {

    /**
     * 복제 요약 — 역할·지연 초·출처·부연. <b>RPO 노출</b>("지금 failover하면 잃을 데이터")은 별도 필드로
     * 두지 않는다 — 그것은 실측 지연(MEASURED lagSeconds) 자체이므로, 표현(반올림·문안·로케일)은 소비자
     * (프론트)가 한다. 백엔드가 한국어 문안을 구우면 MCP 등 비-웹 소비자는 숫자를 못 되찾는다.
     */
    public record Replication(String role, Double lagSeconds, String lagSource, String detail) {}

    /** 백업 요약 — 신선도 상태·마지막 백업 경과·복원 검증 상태. */
    public record Backup(String status, Double elapsedHours, String verifyStatus, int thresholdHours) {}
}
