package io.dbtower.finops.internal.web;

import io.dbtower.finops.internal.FinOpsService;
import io.dbtower.finops.internal.InstanceFinOpsReport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * FinOps 온디맨드 REST (D6) — GET /api/instances/{id}/finops.
 *
 * 낭비 후보(미사용·중복 인덱스, 큰 테이블·과다 인덱싱, 오버프로비저닝 신호)를 종류별로 돌려준다.
 * 항상 새로 계산해 "지금" 상태를 준다. 읽기 전용 진단이라 인증 사용자면 충분하다
 * (SecurityConfig의 anyRequest().authenticated()에 걸린다). 대상 DB를 바꾸지 않는다 — 신호까지만.
 */
@RestController
public class FinOpsController {

    private final FinOpsService finOpsService;

    public FinOpsController(FinOpsService finOpsService) {
        this.finOpsService = finOpsService;
    }

    @GetMapping("/api/instances/{id}/finops")
    public InstanceFinOpsReport finops(@PathVariable Long id) {
        return finOpsService.analyze(id);
    }
}
