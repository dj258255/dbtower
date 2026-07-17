package io.dbtower.alert;

import io.dbtower.alert.internal.domain.AlertMessageMapping;
import io.dbtower.alert.internal.persistence.AlertMessageMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 메시지 매핑 영속(V21) — 핵심 계약은 셋: 왕복(기록한 매핑이 조회된다 = 재시작 생존의 근거),
 * 같은 메시지 재기록은 덮어쓰기(웹훅 재시도 무해), 보존 정리는 오래된 것만 지운다.
 */
@DataJpaTest
class AlertMessageIndexPersistenceTest {

    @Autowired
    private AlertMessageMappingRepository repository;

    @Test
    void 기록한_매핑이_조회되고_재기록은_덮어쓴다() {
        repository.save(new AlertMessageMapping("m1", 7L, LocalDateTime.now()));
        repository.save(new AlertMessageMapping("m1", 9L, LocalDateTime.now()));

        assertThat(repository.findById("m1")).map(AlertMessageMapping::getInstanceId).contains(9L);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 처리_이력은_영속되고_기본은_미처리다() {
        repository.save(new AlertMessageMapping("m2", 3L, LocalDateTime.now()));
        assertThat(repository.findById("m2")).map(AlertMessageMapping::getProcessedAt).isEmpty();

        AlertMessageMapping m = repository.findById("m2").orElseThrow();
        m.markProcessed(LocalDateTime.now());
        repository.save(m);

        assertThat(repository.findById("m2")).map(AlertMessageMapping::getProcessedAt).isPresent();
    }

    @Test
    void 보존_정리는_기준_이전만_지운다() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(new AlertMessageMapping("old", 1L, now.minusDays(40)));
        repository.save(new AlertMessageMapping("fresh", 2L, now));

        long deleted = repository.deleteByCreatedAtBefore(now.minusDays(30));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById("old")).isEmpty();
        assertThat(repository.findById("fresh")).isPresent();
    }
}
