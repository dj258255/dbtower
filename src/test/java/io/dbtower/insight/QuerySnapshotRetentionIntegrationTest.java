package io.dbtower.insight;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 벌크 DELETE가 실제 SQL로 "cutoff 이전 행만" 지우는지 H2에서 확인한다.
 * 단위 테스트는 cutoff 계산까지만 보장하고, JPQL이 의도대로 번역되는지는
 * 진짜 DB를 거쳐야 알 수 있기 때문.
 */
@DataJpaTest
class QuerySnapshotRetentionIntegrationTest {

    @Autowired
    private QuerySnapshotRepository repository;

    @Test
    void 벌크_삭제는_cutoff_이전_행만_지운다() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(snapshot(1L, now.minusDays(10), "old-1"));
        repository.save(snapshot(1L, now.minusDays(8), "old-2"));
        repository.save(snapshot(1L, now.minusDays(3), "recent"));
        repository.save(snapshot(2L, now, "fresh"));
        repository.flush();

        LocalDateTime cutoff = now.minusDays(7);
        int deleted = repository.deleteByCapturedAtBefore(cutoff);

        assertEquals(2, deleted, "7일보다 오래된 두 행만 삭제돼야 한다");
        List<QuerySnapshot> remaining = repository.findAll();
        assertEquals(2, remaining.size());
        // clearAutomatically 덕에 findAll이 삭제 전 캐시가 아니라 DB 상태를 반영한다
        assertTrue(remaining.stream().noneMatch(s -> s.getCapturedAt().isBefore(cutoff)),
                "남은 행은 전부 cutoff 이후여야 한다");
    }

    private QuerySnapshot snapshot(Long instanceId, LocalDateTime capturedAt, String queryId) {
        return new QuerySnapshot(instanceId, capturedAt, queryId, "SELECT 1", 1, 1.0, 1);
    }
}
