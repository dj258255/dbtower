package io.dbtower.alert;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 플랜 변경 이력 조회 — "이 인스턴스에서 최근 옵티마이저가 계획을 갈아탄 쿼리"를 보여준다.
 * 스냅샷은 회귀 감지 시에만 쌓이므로(부하 원칙) 이 목록은 "회귀 + 플랜 변경"의 교집합이다.
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class PlanChangeController {

    private final PlanSnapshotRepository repository;

    public PlanChangeController(PlanSnapshotRepository repository) {
        this.repository = repository;
    }

    public record PlanChangeView(String queryId, String changedAt, String fromShape, String toShape) {
    }

    @GetMapping("/plan-changes")
    public List<PlanChangeView> planChanges(@PathVariable Long id) {
        // 최신순 스냅샷을 쿼리별로 묶어, 연속 스냅샷 쌍(=변경)만 추린다. 첫 관측(기준선)은 변경이 아니다.
        Map<String, List<PlanSnapshot>> byQuery = new LinkedHashMap<>();
        for (PlanSnapshot s : repository.findTop50ByInstanceIdOrderByCapturedAtDesc(id)) {
            byQuery.computeIfAbsent(s.getQueryId(), k -> new ArrayList<>()).add(s);
        }
        List<PlanChangeView> changes = new ArrayList<>();
        for (List<PlanSnapshot> snaps : byQuery.values()) {
            for (int i = 0; i + 1 < snaps.size(); i++) {
                PlanSnapshot cur = snaps.get(i);
                PlanSnapshot prev = snaps.get(i + 1); // 최신순이라 i+1이 직전 스냅샷
                changes.add(new PlanChangeView(cur.getQueryId(), cur.getCapturedAt().toString(),
                        prev.getPlanShape(), cur.getPlanShape()));
            }
        }
        changes.sort((a, b) -> b.changedAt().compareTo(a.changedAt()));
        return changes;
    }
}
