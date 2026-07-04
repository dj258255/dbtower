package io.dbtower.insight;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * findForHourBucket의 hour() JPQL 함수가 실제 DB(H2)에서 "그 시간대(hour) 행만" 뽑는지 확인한다.
 * BaselineServiceTest는 순수 Mock이라 SQL 번역까지는 보장하지 못한다 — 시간대 필터를 DB로 내린 게
 * 핵심 최적화이므로, Hibernate가 hour()를 EXTRACT로 번역하는지 진짜 DB를 거쳐 고정한다.
 */
@DataJpaTest
class BaselineHourBucketIntegrationTest {

    @Autowired
    private QuerySnapshotRepository repository;

    @Test
    void hour_필터는_해당_시간대_행만_반환한다() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 6, 0, 0);
        repository.save(snap(base.withHour(14).withMinute(0), "q14a"));
        repository.save(snap(base.withHour(14).withMinute(30), "q14b"));
        repository.save(snap(base.withHour(9), "q9"));   // 다른 시간대
        repository.save(snap(base.withHour(15), "q15")); // 다른 시간대
        repository.flush();

        List<QuerySnapshot> hour14 = repository.findForHourBucket(
                1L, base.minusDays(1), base.plusDays(1), 14);

        assertEquals(2, hour14.size(), "14시대 두 행만 나와야 한다");
        assertTrue(hour14.stream().allMatch(s -> s.getCapturedAt().getHour() == 14));
    }

    private QuerySnapshot snap(LocalDateTime at, String queryId) {
        return new QuerySnapshot(1L, at, queryId, "SELECT 1", 10, 100.0, 20);
    }
}
