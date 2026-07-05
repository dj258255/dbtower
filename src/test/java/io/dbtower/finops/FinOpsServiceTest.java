package io.dbtower.finops;

import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * FinOps 오케스트레이션 검증 — 미지원은 UNSUPPORTED로 정직 표기, 후보는 CANDIDATES,
 * 분석기 하나가 던져도 나머지가 살아남는지(ERROR 격리), 후보 총수 집계.
 */
class FinOpsServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private final DatabaseInstance instance =
            new DatabaseInstance("db1", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");

    /** 고정 결과를 돌려주는 가짜 분석기. */
    private FinOpsAnalyzer fake(String id, boolean supports, List<WasteCandidate> candidates, boolean fail) {
        return new FinOpsAnalyzer() {
            public String id() { return id; }
            public String title() { return "T-" + id; }
            public boolean supports(DbmsType type) { return supports; }
            public List<WasteCandidate> analyze(DatabaseInstance i, DbmsOperator op) {
                if (fail) {
                    throw new RuntimeException("boom");
                }
                return candidates;
            }
        };
    }

    @Test
    void 미지원_후보없음_후보_오류가_각_상태로_구분되고_후보수가_집계된다() {
        when(operatorFactory.create(instance)).thenReturn(operator);
        WasteCandidate w1 = new WasteCandidate(WasteKind.UNUSED_INDEX, Severity.WARNING, "t.i", "e", "r");
        WasteCandidate w2 = new WasteCandidate(WasteKind.LARGE_TABLE, Severity.INFO, "t", "e", "r");
        FinOpsService svc = new FinOpsService(List.of(
                fake("unsup", false, List.of(), false),
                fake("ok", true, List.of(), false),
                fake("cand", true, List.of(w1, w2), false),
                fake("err", true, List.of(), true)),
                registryService, operatorFactory);

        InstanceFinOpsReport report = svc.analyze(instance);

        assertEquals(4, report.checks().size());
        assertEquals(FinOpsCheck.Status.UNSUPPORTED, status(report, "unsup"));
        assertEquals(FinOpsCheck.Status.OK, status(report, "ok"));
        assertEquals(FinOpsCheck.Status.CANDIDATES, status(report, "cand"));
        assertEquals(FinOpsCheck.Status.ERROR, status(report, "err"));
        assertEquals(2, report.candidateCount());
    }

    @Test
    void 지원_분석기가_없으면_operator를_만들지도_않는다() {
        FinOpsService svc = new FinOpsService(
                List.of(fake("unsup", false, List.of(), false)), registryService, operatorFactory);
        InstanceFinOpsReport report = svc.analyze(instance);
        assertEquals(FinOpsCheck.Status.UNSUPPORTED, status(report, "unsup"));
        Mockito.verifyNoInteractions(operatorFactory);
    }

    private FinOpsCheck.Status status(InstanceFinOpsReport report, String analyzerId) {
        return report.checks().stream()
                .filter(c -> c.analyzer().equals(analyzerId))
                .findFirst().orElseThrow().status();
    }
}
