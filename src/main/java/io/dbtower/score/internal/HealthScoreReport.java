package io.dbtower.score.internal;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전 인스턴스 헬스 스코어 요약 (Phase D8) — 5기종 전체를 점수·등급으로 한눈에, 나쁜 순으로.
 * GET /api/health-score와 웹 콘솔 대시보드 상단 카드가 쓰는 단위. "어디부터 볼지"를 기계가 정렬해 답한다.
 *
 * <b>나쁜 순 정렬:</b> health가 down인 인스턴스를 최우선으로 위에, 그다음 점수 오름차순(낮을수록 위),
 * 동점은 id 순. 죽은 것·백업 없는 것처럼 감점이 큰 인스턴스가 상단에 온다.
 *
 * @param generatedAt   집계 시각
 * @param total         전체 인스턴스 수
 * @param partialCount  일부 신호가 데이터 부족·수집 실패라 부분 데이터로 계산된 인스턴스 수
 * @param gradeCounts   등급별 인스턴스 수(A~F, 0인 등급도 포함해 순서 고정)
 * @param instances     인스턴스별 스코어(나쁜 순 정렬)
 */
public record HealthScoreReport(LocalDateTime generatedAt, int total, int partialCount,
                                Map<String, Integer> gradeCounts, List<HealthScore> instances) {

    /** 나쁜 순: down 먼저 → 점수 오름차순 → id 순. */
    private static final Comparator<HealthScore> WORST_FIRST = Comparator
            .comparing((HealthScore h) -> h.down() ? 0 : 1)
            .thenComparingInt(HealthScore::score)
            .thenComparing(h -> h.instanceId() == null ? Long.MAX_VALUE : h.instanceId());

    /** 집계·정렬은 호출부가 아니라 여기서 — 스코어 목록만 주면 카운트·정렬·등급 분포를 파생한다. */
    public static HealthScoreReport of(LocalDateTime generatedAt, List<HealthScore> scores) {
        Map<String, Integer> grades = new LinkedHashMap<>();
        for (String g : List.of(HealthScore.GRADE_A, HealthScore.GRADE_B, HealthScore.GRADE_C,
                HealthScore.GRADE_D, HealthScore.GRADE_F)) {
            grades.put(g, 0);
        }
        int partial = 0;
        for (HealthScore s : scores) {
            grades.merge(s.grade(), 1, Integer::sum);
            if (s.partial()) {
                partial++;
            }
        }
        List<HealthScore> sorted = scores.stream().sorted(WORST_FIRST).toList();
        return new HealthScoreReport(generatedAt, scores.size(), partial, grades, sorted);
    }
}
