package io.dbtower.insight;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySnapshotMaskingTest {

    @Test
    void 통계_스냅샷에_유틸리티_문장의_자격증명_리터럴을_저장하지_않는다() {
        QuerySnapshot snapshot = new QuerySnapshot(1L, LocalDateTime.now(), "q1",
                "CREATE ROLE demo LOGIN PASSWORD 'real-secret'", 1, 2.0, 0);

        assertThat(snapshot.getQueryText()).isEqualTo("CREATE ROLE demo LOGIN PASSWORD ?");
        assertThat(snapshot.getQueryText()).doesNotContain("real-secret");
    }
}
