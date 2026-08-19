package io.dbtower.score.internal.web;

import io.dbtower.score.InstanceOverview;
import io.dbtower.score.internal.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대상별 운영 종합 REST (DBRE) — GET /api/instances/{id}/overview.
 * "이 DB가 무엇인지 + 지금 어떤지"를 한 번에. 읽기 전용 집계라 인증 사용자면 충분하다.
 */
@RestController
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/api/instances/{id}/overview")
    public InstanceOverview overview(@PathVariable Long id) {
        return overviewService.overviewFor(id);
    }
}
