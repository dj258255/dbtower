package io.dbtower.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-2 plan_snapshot 보존 — (instance, query)별 최신 N개만 남기고 나머지를 지우는 네이티브 윈도우 쿼리가
 * 실제 SQL로 의도대로 번역되는지 H2(PostgreSQL 모드)에서 확인한다. 단위 로직이 아니라 ROW_NUMBER() 파티션·
 * DELETE 서브쿼리가 DB에서 올바로 도는지가 핵심이라 진짜 DB를 거친다.
 */
@DataJpaTest
class PlanSnapshotRetentionIntegrationTest {

    @Autowired
    private PlanSnapshotRepository repository;

    private PlanSnapshot snap(long instanceId, String queryId, LocalDateTime at, String shape) {
        return new PlanSnapshot(instanceId, queryId, "h-" + shape, shape, at);
    }

    @Test
    void 그룹별_최신_N개만_남기고_삭제한다() {
        LocalDateTime now = LocalDateTime.now();
        // (1, qA): 5건 — 3건 삭제 대상
        for (int i = 0; i < 5; i++) {
            repository.save(snap(1L, "qA", now.minusMinutes(i), "qA-" + i));
        }
        // (1, qB): 3건 — 1건 삭제 대상
        for (int i = 0; i < 3; i++) {
            repository.save(snap(1L, "qB", now.minusMinutes(i), "qB-" + i));
        }
        // (2, qA): 2건 — 삭제 없음(임계 이하)
        for (int i = 0; i < 2; i++) {
            repository.save(snap(2L, "qA", now.minusMinutes(i), "2qA-" + i));
        }
        repository.flush();

        int deleted = repository.deleteExceedingPerQuery(2);

        assertThat(deleted).isEqualTo(4); // (5-2) + (3-2) + 0
        List<PlanSnapshot> remaining = repository.findAll();
        assertThat(remaining).hasSize(6);

        // 각 그룹에서 남은 것은 "가장 최신 2개"여야 한다 — qA(instance 1)는 i=0,1(가장 최근)만 남는다
        List<String> inst1qA = remaining.stream()
                .filter(s -> s.getInstanceId() == 1L && s.getQueryId().equals("qA"))
                .map(PlanSnapshot::getPlanShape).sorted().toList();
        assertThat(inst1qA).containsExactly("qA-0", "qA-1");
    }

    @Test
    void keep이_그룹_크기보다_크면_아무것도_안_지운다() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(snap(9L, "qX", now, "only"));
        repository.flush();

        assertThat(repository.deleteExceedingPerQuery(20)).isZero();
        assertThat(repository.findAll()).hasSize(1);
    }
}
