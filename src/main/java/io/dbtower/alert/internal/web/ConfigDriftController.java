package io.dbtower.alert.internal.web;

import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.alert.internal.persistence.ConfigDriftDao.ParamChangeRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 설정 드리프트 조회 API (운영 병목 아크 B1, P3·P4). 파라미터 값이 실릴 수 있어 파라미터
 * 조회·diff와 같은 ADMIN 경계에 둔다(SecurityConfig — 인프라 형상·자격증명 노출 방지).
 */
@RestController
public class ConfigDriftController {

    private final ConfigDriftService driftService;

    public ConfigDriftController(ConfigDriftService driftService) {
        this.driftService = driftService;
    }

    /** 설정 변경 이력 타임라인(P3) — 최신순. "누가"는 표기하지 않는다(대상 DB 감사 로그의 몫). */
    @GetMapping("/api/instances/{id}/config-drift")
    public List<ParamChangeRow> timeline(@PathVariable Long id,
                                         @RequestParam(defaultValue = "100") int limit) {
        return driftService.timeline(id, Math.min(limit, 500));
    }

    /**
     * 플랜 플립 대조(P4) — 기준 시각 ±hours 안의 설정 변경 수. 플랜 플립 카드가
     * "그 무렵 설정이 바뀌었나"를 물을 때 쓴다.
     */
    @GetMapping("/api/instances/{id}/config-drift/around")
    public AroundResult around(@PathVariable Long id,
                               @RequestParam String at,
                               @RequestParam(defaultValue = "24") int hours) {
        LocalDateTime center = LocalDateTime.parse(at);
        return new AroundResult(driftService.changesAround(id, center, hours), hours);
    }

    public record AroundResult(int changeCount, int windowHours) {
    }
}
