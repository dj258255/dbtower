package io.dbtower.advisor;

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
 * 오케스트레이션 검증 — 미지원은 UNSUPPORTED로 정직 표기, 위반은 심각도별 집계,
 * Advisor 하나가 던져도 나머지가 살아남는지(ERROR 격리).
 */
class AdvisorServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private final DatabaseInstance instance =
            new DatabaseInstance("db1", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");

    /** 고정 결과를 돌려주는 가짜 Advisor. */
    private Advisor fake(String id, boolean supports, List<AdvisorFinding> findings, boolean fail) {
        return new Advisor() {
            public String id() { return id; }
            public String title() { return "T-" + id; }
            public boolean supports(DbmsType type) { return supports; }
            public List<AdvisorFinding> inspect(DatabaseInstance i, DbmsOperator op) {
                if (fail) {
                    throw new RuntimeException("boom");
                }
                return findings;
            }
        };
    }

    private AdvisorService service(List<Advisor> advisors) {
        when(operatorFactory.create(instance)).thenReturn(operator);
        return new AdvisorService(advisors, registryService, operatorFactory);
    }

    @Test
    void 미지원_통과_위반_오류가_각_상태로_구분되고_심각도가_집계된다() {
        AdvisorFinding critical = new AdvisorFinding(Severity.CRITICAL, "t", "d", "r");
        AdvisorFinding warning = new AdvisorFinding(Severity.WARNING, "t", "d", "r");
        AdvisorService svc = service(List.of(
                fake("unsup", false, List.of(), false),
                fake("ok", true, List.of(), false),
                fake("viol", true, List.of(critical, warning), false),
                fake("err", true, List.of(), true)));

        InstanceAdvisorReport report = svc.inspect(instance);

        assertEquals(4, report.checks().size());
        assertEquals(AdvisorCheck.Status.UNSUPPORTED, status(report, "unsup"));
        assertEquals(AdvisorCheck.Status.OK, status(report, "ok"));
        assertEquals(AdvisorCheck.Status.VIOLATIONS, status(report, "viol"));
        assertEquals(AdvisorCheck.Status.ERROR, status(report, "err"));
        assertEquals(1, report.critical());
        assertEquals(1, report.warning());
        assertEquals(0, report.info());
    }

    @Test
    void 지원_Advisor가_없으면_operator를_만들지도_않는다() {
        AdvisorService svc = new AdvisorService(
                List.of(fake("unsup", false, List.of(), false)), registryService, operatorFactory);
        InstanceAdvisorReport report = svc.inspect(instance);
        assertEquals(AdvisorCheck.Status.UNSUPPORTED, status(report, "unsup"));
        Mockito.verifyNoInteractions(operatorFactory);
    }

    private AdvisorCheck.Status status(InstanceAdvisorReport report, String advisorId) {
        return report.checks().stream()
                .filter(c -> c.advisor().equals(advisorId))
                .findFirst().orElseThrow().status();
    }
}
