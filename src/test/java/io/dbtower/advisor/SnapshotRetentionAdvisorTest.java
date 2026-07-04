package io.dbtower.advisor;

import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 스냅샷 보존 미설정 규칙 — retention<=0 + 실제 적재 있을 때만 경고, operator는 쓰지 않는다. */
class SnapshotRetentionAdvisorTest {

    private final QuerySnapshotRepository repository = Mockito.mock(QuerySnapshotRepository.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private DatabaseInstance instance() {
        // id는 리플렉션 없이 지정하기 어려워 findAll 경로 대신 sumByBatch가 어떤 id로 불려도 되게 any()로 스텁
        return new DatabaseInstance("db1", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");
    }

    private List<QuerySnapshotRepository.BatchTotal> batches(int n) {
        return Collections.nCopies(n, Mockito.mock(QuerySnapshotRepository.BatchTotal.class));
    }

    @Test
    void 보존이_꺼져있고_스냅샷이_쌓여있으면_경고() {
        when(repository.sumByBatch(any(), any(), any())).thenReturn(batches(42));
        SnapshotRetentionAdvisor advisor = new SnapshotRetentionAdvisor(repository, 0);
        List<AdvisorFinding> f = advisor.inspect(instance(), operator);
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).detail().contains("42"));
        Mockito.verifyNoInteractions(operator); // 대상 DB를 건드리지 않는다
    }

    @Test
    void 보존이_켜져있으면_통과() {
        SnapshotRetentionAdvisor advisor = new SnapshotRetentionAdvisor(repository, 7);
        assertTrue(advisor.inspect(instance(), operator).isEmpty());
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void 보존이_꺼져도_아직_수집된_스냅샷이_없으면_통과() {
        when(repository.sumByBatch(any(), any(), any())).thenReturn(batches(0));
        SnapshotRetentionAdvisor advisor = new SnapshotRetentionAdvisor(repository, -1);
        assertTrue(advisor.inspect(instance(), operator).isEmpty());
    }

    @Test
    void 모든_기종을_지원한다() {
        SnapshotRetentionAdvisor advisor = new SnapshotRetentionAdvisor(repository, 0);
        for (DbmsType t : DbmsType.values()) {
            assertTrue(advisor.supports(t));
        }
    }
}
