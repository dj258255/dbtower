package io.dbtower.score.internal.web;

import io.dbtower.score.internal.HealthScore;
import io.dbtower.score.internal.HealthScoreReport;
import io.dbtower.score.internal.ScoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합 헬스 스코어 REST (Phase D8).
 *
 * - GET /api/health-score            — 전 인스턴스 요약(점수·등급·주요 감점 사유, 나쁜 순 정렬)
 * - GET /api/instances/{id}/health-score — 인스턴스 하나의 상세 분해(신호별 기여)
 *
 * 읽기 전용 종합이라 인증 사용자면 충분하다(SecurityConfig의 anyRequest().authenticated()에 걸린다).
 * 항상 지금 신호로 새로 합산한다(캐시 아님).
 */
@RestController
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @GetMapping("/api/health-score")
    public HealthScoreReport all() {
        return scoreService.reportAll();
    }

    @GetMapping("/api/instances/{id}/health-score")
    public HealthScore one(@PathVariable Long id) {
        return scoreService.evaluate(id);
    }
}
