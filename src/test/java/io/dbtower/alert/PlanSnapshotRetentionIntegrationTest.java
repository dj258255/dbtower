package io.dbtower.alert;

import io.dbtower.alert.internal.domain.PlanSnapshot;
import io.dbtower.alert.internal.persistence.PlanSnapshotRepository;
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

        // cutoff를 미래로 줘 시간 하한 없이 순수 카운트 동작을 검증한다(minAgeHours<=0 경로).
        int deleted = repository.deleteExceedingPerQuery(2, now.plusMinutes(1));

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

        assertThat(repository.deleteExceedingPerQuery(20, now.plusMinutes(1))).isZero();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void 시간_하한보다_어린_행은_세대_초과여도_보존된다_D2() {
        // 플랜이 자주 뒤집히는 쿼리 시나리오: keep=2 초과분이 5건인데, 그중 3건은 최근 24h 안(어린 행).
        // cutoff=now-48h 기준 — 어린 행은 살아남고, 48h를 넘긴 초과분만 지워져야 한다.
        // lakehouse가 "어제 하루창"을 D+1에 뽑는 계약(CONTRACT §1-1) 보장이 이 하한의 존재 이유다.
        LocalDateTime now = LocalDateTime.now();
        // 오래된 4건(3~6일 전) + 어린 3건(1~23시간 전) = 7건, keep=2
        for (int d = 3; d <= 6; d++) {
            repository.save(snap(5L, "qHot", now.minusDays(d), "old-" + d));
        }
        repository.save(snap(5L, "qHot", now.minusHours(1), "young-1h"));
        repository.save(snap(5L, "qHot", now.minusHours(12), "young-12h"));
        repository.save(snap(5L, "qHot", now.minusHours(23), "young-23h"));
        repository.flush();

        int deleted = repository.deleteExceedingPerQuery(2, now.minusHours(48));

        // 최신 2 = young-1h, young-12h(보존 대상 아님이어도 rn<=keep), young-23h는 rn=3이지만
        // cutoff(48h)보다 어리므로 보존. 삭제되는 것은 48h 넘긴 old-3~6 전부(rn 4~7) = 4건.
        assertThat(deleted).isEqualTo(4);
        List<String> shapes = repository.findAll().stream()
                .map(PlanSnapshot::getPlanShape).sorted().toList();
        assertThat(shapes).containsExactly("young-12h", "young-1h", "young-23h");
    }
}
