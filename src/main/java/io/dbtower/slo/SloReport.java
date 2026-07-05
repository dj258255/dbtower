package io.dbtower.slo;

import io.dbtower.registry.DbmsType;

import java.time.LocalDateTime;

/**
 * 인스턴스 하나의 SLO 종합 (Phase D4) — 레이턴시·가용성 SLI, 목표(SLO), 에러 버짓 소진율·번인 레이트, 판정.
 * GET /api/instances/{id}/slo와 웹 콘솔 "SLO / 에러 버짓" 카드가 쓰는 단위.
 *
 * overall 판정 우선순위: 하나라도 위반(레이턴시/가용성 BREACHING, 버짓 EXHAUSTED)이면 BREACHING,
 * 버짓 소진이 임박(WARNING)이면 AT_RISK, 실측 신호가 목표를 지키면 MEETING, 둘 다 표본 부족이면 INSUFFICIENT_DATA.
 *
 * @param instanceId   인스턴스 id
 * @param instanceName 인스턴스 이름
 * @param type         기종
 * @param evaluatedAt  평가 시각
 * @param latency      레이턴시 SLI(출처·폴백 표기 포함)
 * @param availability 가용성 SLI
 * @param errorBudget  에러 버짓·번인 레이트
 * @param verdict      MEETING/AT_RISK/BREACHING/INSUFFICIENT_DATA
 */
public record SloReport(Long instanceId, String instanceName, DbmsType type, LocalDateTime evaluatedAt,
                        LatencySli latency, AvailabilitySli availability, ErrorBudget errorBudget,
                        String verdict) {

    public static final String MEETING = "MEETING";
    public static final String AT_RISK = "AT_RISK";
    public static final String BREACHING = "BREACHING";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
}
