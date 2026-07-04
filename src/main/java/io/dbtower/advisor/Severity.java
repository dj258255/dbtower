package io.dbtower.advisor;

/**
 * Advisor 지적의 심각도 — 웹 콘솔이 색으로 묶어 보여주는 3단계(PMM Advisors와 동일 구도).
 *
 * CRITICAL: 데이터 유실·모니터링 실명 등 지금 조치해야 하는 신호.
 * WARNING:  운영상 위험하거나 곧 문제가 될 설정·구조.
 * INFO:     알아두면 좋은 기본값·후보(당장 위험하진 않음).
 */
public enum Severity {
    CRITICAL,
    WARNING,
    INFO
}
