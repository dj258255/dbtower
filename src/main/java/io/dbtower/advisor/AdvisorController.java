package io.dbtower.advisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Advisors 온디맨드 REST (Phase D2) — GET /api/instances/{id}/advisors.
 *
 * 항상 새로 계산해 "지금" 상태를 돌려준다(스윕 캐시가 아니라 라이브). 읽기 전용 진단이라 VIEWER면
 * 충분하다(SecurityConfig의 anyRequest().authenticated()에 걸린다 — 별도 권한 경계 불요).
 */
@RestController
public class AdvisorController {

    private final AdvisorService advisorService;

    public AdvisorController(AdvisorService advisorService) {
        this.advisorService = advisorService;
    }

    @GetMapping("/api/instances/{id}/advisors")
    public InstanceAdvisorReport advisors(@PathVariable Long id) {
        return advisorService.inspect(id);
    }
}
