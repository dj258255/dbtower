package io.dbtower.workbench.internal;

import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.QueryResult;
import io.dbtower.operator.model.ResultColumn;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.internal.WorkbenchService.QueryView;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.domain.MaskingRule;
import io.dbtower.workbench.internal.domain.MaskingStrategy;
import io.dbtower.workbench.internal.domain.WorkbenchQueryLog;
import io.dbtower.workbench.internal.persistence.MaskingRuleRepository;
import io.dbtower.workbench.internal.persistence.WorkbenchQueryLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 워크벤치 정책 흐름의 순서 계약 — 분류에서 막히면 대상 DB에 닿지 않고, 조회 계정이 없으면 모니터 계정으로 대신
 * 실행하지 않으며, 거부·실패도 성공과 같은 기록에 남는다.
 */
class WorkbenchServiceTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final ConsoleCredentialService credentials = mock(ConsoleCredentialService.class);
    private final DbmsOperatorFactory operators = mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = mock(DbmsOperator.class);
    private final MaskingRuleRepository rules = mock(MaskingRuleRepository.class);
    private final WorkbenchQueryLogRepository logs = mock(WorkbenchQueryLogRepository.class);
    private final DatabaseInstance instance = new DatabaseInstance("orders", DbmsType.MYSQL, "h", 3306, "sample", "monitor", "p");

    private WorkbenchService service;

    @BeforeEach
    void setUp() {
        service = new WorkbenchService(registry, credentials, operators, rules, logs, new QueryMasker(true, false), 200, 15);
        when(registry.findById(1L)).thenReturn(instance);
        when(operators.create(instance)).thenReturn(operator);
        when(rules.findApplicable(1L)).thenReturn(List.of(new MaskingRule(null, "*email*", MaskingStrategy.PARTIAL, "이메일")));
    }

    private WorkbenchQueryLog savedLog() {
        ArgumentCaptor<WorkbenchQueryLog> captor = ArgumentCaptor.forClass(WorkbenchQueryLog.class);
        verify(logs).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 변경_문장은_대상_DB에_닿기_전에_거부되고_기록된다() {
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class,
                () -> service.run(1L, "UPDATE users SET email = 'a@b.c' WHERE id = 7", null));

        assertEquals(409, e.status(), "변경 요청으로 가라는 뜻");
        verifyNoInteractions(operators);
        verifyNoInteractions(credentials);
        WorkbenchQueryLog log = savedLog();
        assertEquals("REJECTED", log.getOutcome());
        assertEquals("NEEDS_APPROVAL", log.getTier());
        assertFalse(log.getStatement().contains("a@b.c"), "감사 기록에 리터럴 값이 새면 안 된다: " + log.getStatement());
    }

    @Test
    void 차단_문장은_400이다() {
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class,
                () -> service.run(1L, "COMMIT; DROP SCHEMA public CASCADE", null));
        assertEquals(400, e.status());
        verifyNoInteractions(operators);
    }

    @Test
    void 조회_계정이_없으면_모니터_계정으로_대신_실행하지_않는다() {
        when(credentials.find(1L, CredentialPurpose.READ)).thenReturn(Optional.empty());

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.run(1L, "SELECT 1", null));

        assertEquals(409, e.status());
        verifyNoInteractions(operators);
        assertEquals("REJECTED", savedLog().getOutcome());
    }

    @Test
    void 읽기는_조회_계정으로_실행하고_마스킹된_결과를_돌려준다() {
        ConsoleCredential reader = new ConsoleCredential("reader", "pw");
        when(credentials.find(1L, CredentialPurpose.READ)).thenReturn(Optional.of(reader));
        when(operator.executeReadOnly(eq(reader), anyString(), eq(50), eq(15))).thenReturn(new QueryResult(
                List.of(new ResultColumn("id", "id", "INT"), new ResultColumn("email", "email", "VARCHAR")),
                List.of(Arrays.asList(1, "hong@example.com")), true, 12));

        QueryView view = service.run(1L, "SELECT id, email FROM customers", 50);

        assertEquals(List.of("email"), view.maskedColumns());
        assertEquals("ho************om", view.rows().get(0).get(1));
        assertTrue(view.truncated());
        WorkbenchQueryLog log = savedLog();
        assertEquals("OK", log.getOutcome());
        assertEquals(1, log.getRowCount());
        assertEquals("email", log.getMaskedColumns());
    }

    @Test
    void 실행_실패는_422로_돌려주고_ERROR로_기록한다() {
        when(credentials.find(1L, CredentialPurpose.READ)).thenReturn(Optional.of(new ConsoleCredential("reader", "pw")));
        when(operator.executeReadOnly(any(), anyString(), anyInt(), anyInt()))
                .thenThrow(new OperatorException("MYSQL 콘솔 조회 실패: Table 'sample.nope' doesn't exist"));

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.run(1L, "SELECT * FROM nope", null));

        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("doesn't exist"), "사용자가 고칠 수 있게 DB 오류를 그대로 보여준다");
        assertEquals("ERROR", savedLog().getOutcome());
    }

    @Test
    void 내보내기는_사유가_없으면_실행하지_않는다() {
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.export(1L, "SELECT 1", "  ab "));
        assertEquals(400, e.status());
        verifyNoInteractions(operators);
        verifyNoInteractions(logs);
    }

    @Test
    void CSV는_수식_주입을_텍스트로_고정하고_구분자를_인용한다() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvWriter.cell("=HYPERLINK(\"x\")"));
        assertEquals("'@SUM(A1)", CsvWriter.cell("@SUM(A1)"));
        assertEquals("-5", CsvWriter.cell(-5), "숫자 음수는 수식이 아니다");
        assertEquals("\"a,b\"", CsvWriter.cell("a,b"));
        assertEquals("", CsvWriter.cell(null));
    }
}
