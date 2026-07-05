package io.dbtower.slo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB SLO / 에러 버짓 REST (Phase D4).
 *
 * - GET /api/instances/{id}/slo — 인스턴스 하나의 SLI 현재값·SLO 목표·버짓 소진율·번인 레이트·판정
 *
 * 읽기 전용 진단이라 인증 사용자면 충분하다(SecurityConfig의 anyRequest().authenticated()에 걸린다).
 * 항상 지금 이력·현재 백분위로 새로 계산한다(캐시 아님).
 */
@RestController
public class SloController {

    private final SloService sloService;

    public SloController(SloService sloService) {
        this.sloService = sloService;
    }

    @GetMapping("/api/instances/{id}/slo")
    public SloReport slo(@PathVariable Long id) {
        return sloService.evaluate(id);
    }
}
