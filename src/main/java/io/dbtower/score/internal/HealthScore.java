package io.dbtower.score.internal;

import io.dbtower.score.internal.SignalContribution.State;
import io.dbtower.score.internal.SignalContribution.Signal;
import io.dbtower.registry.DbmsType;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 인스턴스 하나의 통합 헬스 스코어 (Phase D8) — 다섯 신호의 기여를 합산한 한 점수/등급과 그 분해.
 * GET /api/instances/{id}/health-score와 웹 콘솔 "헬스 스코어" 카드가 쓰는 단위.
 *
 * <b>산식(응답·주석에 명시):</b> 100점에서 시작해 점수에 포함되는 신호(OK·PENALIZED)의 감점을 모두 뺀다.
 * 결과는 0~100으로 바닥친다(합산 감점이 100을 넘어도 음수가 되지 않는다). 등급은 점수 구간으로 A~F.
 * 데이터 부족·수집 실패 신호(INSUFFICIENT_DATA·ERROR)는 감점 없이 제외하고 partial=true로 "부분 데이터"를 표기한다 —
 * 없는 신호를 0점·장애로 오판하지 않기 위해서다.
 *
 * @param instanceId    인스턴스 id
 * @param instanceName  인스턴스 이름
 * @param type          기종
 * @param evaluatedAt   평가 시각
 * @param score         0~100
 * @param grade         A/B/C/D/F
 * @param down          health가 down인가(나쁜 순 정렬 최우선 키)
 * @param partial       데이터 부족·수집 실패 신호가 하나라도 있어 일부만으로 계산했는가
 * @param countedSignals 점수 계산에 실제로 포함된 신호 수(투명성)
 * @param contributions 신호별 기여(감점 큰 순 정렬)
 */
public record HealthScore(Long instanceId, String instanceName, DbmsType type, LocalDateTime evaluatedAt,
                          int score, String grade, boolean down, boolean partial,
                          int countedSignals, List<SignalContribution> contributions) {

    public static final String GRADE_A = "A";
    public static final String GRADE_B = "B";
    public static final String GRADE_C = "C";
    public static final String GRADE_D = "D";
    public static final String GRADE_F = "F";

    /** 표시용 정렬 — 감점 큰 신호를 위로(주요 감점 사유), 같은 감점은 데이터 부족/에러를 그 아래로. */
    private static final Comparator<SignalContribution> BY_IMPACT = Comparator
            .comparingDouble(SignalContribution::penalty).reversed()
            .thenComparing(c -> c.missing() ? 1 : 0)
            .thenComparing(c -> c.signal().ordinal());

    /**
     * 신호 기여 목록에서 점수·등급·부분 데이터 여부를 파생한다 — 호출부가 계산하지 않도록 여기서 합산한다.
     * 점수 계산에 포함되는 것은 counted() 신호(OK·PENALIZED)뿐이다. missing 신호는 감점 없이 partial만 올린다.
     */
    public static HealthScore of(Long instanceId, String instanceName, DbmsType type, LocalDateTime evaluatedAt,
                                 List<SignalContribution> contributions) {
        double totalPenalty = 0;
        int counted = 0;
        boolean partial = false;
        boolean down = false;
        for (SignalContribution c : contributions) {
            if (c.counted()) {
                totalPenalty += c.penalty();
                counted++;
            }
            if (c.missing()) {
                partial = true;
            }
            if (c.signal() == Signal.HEALTH && c.state() == State.PENALIZED) {
                down = true;
            }
        }
        int score = (int) Math.round(Math.max(0, Math.min(100, 100 - totalPenalty)));
        List<SignalContribution> sorted = contributions.stream().sorted(BY_IMPACT).toList();
        return new HealthScore(instanceId, instanceName, type, evaluatedAt,
                score, gradeOf(score), down, partial, counted, sorted);
    }

    /** 점수 → 등급 구간. A(90+)/B(80+)/C(70+)/D(60+)/F(그 미만). */
    public static String gradeOf(int score) {
        if (score >= 90) return GRADE_A;
        if (score >= 80) return GRADE_B;
        if (score >= 70) return GRADE_C;
        if (score >= 60) return GRADE_D;
        return GRADE_F;
    }
}
