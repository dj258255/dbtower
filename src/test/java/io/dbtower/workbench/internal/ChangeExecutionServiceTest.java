package io.dbtower.workbench.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.audit.AuditTrail;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.SchemaDiffService;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.ChangeCommitUncertainException;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangeOutcome.Probe;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.operator.model.RevertPlan.Conflict;
import io.dbtower.operator.model.RowImage;
import io.dbtower.operator.model.RowImage.ImageColumn;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ChangeTicketGate;
import io.dbtower.review.ChangeTicketGate.ChangeTicket;
import io.dbtower.security.SecretCipher;
import io.dbtower.workbench.internal.ChangeExecutionService.ExecutionView;
import io.dbtower.workbench.internal.ResultMasker.Policy;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.domain.ChangeExecution;
import io.dbtower.workbench.internal.domain.ChangeExecution.Action;
import io.dbtower.workbench.internal.domain.ChangeExecution.Outcome;
import io.dbtower.workbench.internal.domain.MaskingStrategy;
import io.dbtower.workbench.internal.persistence.ChangeExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 승인 티켓 실행의 순서 계약. 거부될 수 있는 것은 전부 실행권(EXECUTING)을 잡기 전에 거부되고, 대상 DB가 롤백을 확정한 실패만
 * 실행권을 돌려주며, 커밋 여부를 모르면 돌려주지 않는다. 사본은 암호화돼 남고 화면에는 마스킹을 거쳐 나간다.
 */
class ChangeExecutionServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChangeTicketGate gate = mock(ChangeTicketGate.class);
    private final RegistryService registry = mock(RegistryService.class);
    private final ConsoleCredentialService credentials = mock(ConsoleCredentialService.class);
    private final DbmsOperatorFactory operators = mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = mock(DbmsOperator.class);
    private final ChangeExecutionRepository executions = mock(ChangeExecutionRepository.class);
    private final WorkbenchService workbench = mock(WorkbenchService.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final SecretCipher cipher = new SecretCipher(KEY);
    private final DatabaseInstance instance = new DatabaseInstance("orders", DbmsType.MYSQL, "h", 3306, "sample", "monitor", "p");
    private final ConsoleCredential writer = new ConsoleCredential("dbtower_writer", "secret");

    private ChangeExecutionService service;

    private static final List<ImageColumn> COLUMNS = List.of(
            new ImageColumn("id", Types.INTEGER, "INT"),
            new ImageColumn("email", Types.VARCHAR, "VARCHAR"),
            new ImageColumn("grade", Types.VARCHAR, "VARCHAR"));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(instance, "id", 1L);
        service = new ChangeExecutionService(gate, registry, credentials, operators, executions, workbench,
                mock(SchemaDiffService.class), mock(ComparisonService.class), cipher, audit, 10_000, 30, 7);
        when(registry.findById(1L)).thenReturn(instance);
        when(operators.create(instance)).thenReturn(operator);
        when(credentials.find(1L, CredentialPurpose.WRITE)).thenReturn(Optional.of(writer));
        when(executions.save(any(ChangeExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbench.policies(1L)).thenReturn(List.of(new Policy("*email*", MaskingStrategy.PARTIAL)));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void ticket(String status, String sql, String verifySql) {
        when(gate.ticket(7L)).thenReturn(new ChangeTicket(7L, 1L, sql, verifySql, status, "dev", "admin"));
    }

    private static RowImage image(List<String> keys, List<List<String>> rows) {
        return new RowImage(COLUMNS, keys, rows);
    }

    private static ChangeOutcome updated(boolean committed) {
        RowImage before = image(List.of("id"), List.of(List.of("3", "lee@example.com", "SILVER")));
        RowImage after = image(List.of("id"), List.of(List.of("3", "lee@example.com", "VIP")));
        return new ChangeOutcome(committed, 1, before, after, null, null, null, 12);
    }

    @Test
    void 승인되지_않은_티켓은_실행권을_잡기_전에_거부되고_대상_DB에_닿지_않는다() {
        ticket("PENDING", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false));

        assertEquals(409, e.status());
        verify(gate, never()).claimExecution(any());
        verifyNoInteractions(operators);
    }

    @Test
    void 승인됐어도_차단_문장은_실행하지_않는다() {
        ticket("APPROVED", "TRUNCATE customers", null);

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false));

        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("차단"));
        verify(gate, never()).claimExecution(any());
    }

    @Test
    void 검증_조회가_읽기_문장이_아니면_실행권을_잡기_전에_거부한다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", "DELETE FROM customers");

        assertEquals(422, assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false)).status());
        verify(gate, never()).claimExecution(any());
    }

    @Test
    void 변경_계정이_없으면_모니터_계정으로_대신하지_않고_실행권도_잡지_않는다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(credentials.find(1L, CredentialPurpose.WRITE)).thenReturn(Optional.empty());

        assertEquals(409, assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false)).status());
        verify(gate, never()).claimExecution(any());
        verify(operator, never()).executeChange(any(), any());
    }

    @Test
    void 사본을_잡을_수_없는_문장은_명시해야만_캡처_없이_실행한다() {
        ticket("APPROVED", "UPDATE a SET x = b.y FROM b WHERE a.id = b.id", null);

        assertEquals(409, assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false)).status());
        verify(gate, never()).claimExecution(any());

        when(gate.claimExecution(7L)).thenReturn(true);
        when(operator.executeChange(eq(writer), any())).thenReturn(
                new ChangeOutcome(true, 4, null, null, "캡처 없이 실행하기로 사람이 인정한 변경이다", null, null, 5));
        ExecutionView view = service.execute(7L, true);

        ArgumentCaptor<ChangePlan> plan = ArgumentCaptor.forClass(ChangePlan.class);
        verify(operator).executeChange(eq(writer), plan.capture());
        assertEquals(Kind.UNCAPTURED, plan.getValue().kind());
        assertFalse(view.rollbackAvailable());
        assertEquals("COMMITTED", view.outcome());
    }

    @Test
    void 다른_요청이_실행권을_가져가면_대상_DB에_닿지_않는다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(gate.claimExecution(7L)).thenReturn(false);

        assertEquals(409, assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false)).status());
        verify(operator, never()).executeChange(any(), any());
    }

    @Test
    void 대상_DB가_롤백한_실패는_기록하고_실행권을_돌려준다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(gate.claimExecution(7L)).thenReturn(true);
        when(operator.executeChange(any(), any())).thenThrow(new OperatorException("영향 행 수(2)가 변경 전 사본 행 수(1)와 달라 커밋하지 않았다"));

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false));

        assertEquals(422, e.status());
        verify(gate).releaseExecution(7L);
        verify(gate, never()).completeExecution(any(), any(), any(), anyLong());
        ArgumentCaptor<ChangeExecution> saved = ArgumentCaptor.forClass(ChangeExecution.class);
        verify(executions, atLeastOnce()).save(saved.capture());
        assertEquals(Outcome.FAILED, saved.getValue().getOutcome());
    }

    @Test
    void 커밋_여부를_모르면_실행권을_돌려주지_않아_같은_변경이_두_번_나가지_않는다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(gate.claimExecution(7L)).thenReturn(true);
        when(operator.executeChange(any(), any())).thenThrow(new ChangeCommitUncertainException("커밋 호출 실패", null));

        assertEquals(500, assertThrows(WorkbenchRejection.class, () -> service.execute(7L, false)).status());
        verify(gate, never()).releaseExecution(any());
        ArgumentCaptor<ChangeExecution> saved = ArgumentCaptor.forClass(ChangeExecution.class);
        verify(executions, atLeastOnce()).save(saved.capture());
        assertEquals(Outcome.UNCERTAIN, saved.getValue().getOutcome());
    }

    @Test
    void 성공하면_사본을_암호화해_남기고_화면에는_마스킹된_전후_비교를_준다() {
        ticket("APPROVED", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(gate.claimExecution(7L)).thenReturn(true);
        when(operator.executeChange(any(), any())).thenReturn(updated(true));

        ExecutionView view = service.execute(7L, false);

        verify(gate).completeExecution(7L, 1L, "admin", 1);
        ArgumentCaptor<ChangeExecution> saved = ArgumentCaptor.forClass(ChangeExecution.class);
        verify(executions, atLeastOnce()).save(saved.capture());
        ChangeExecution record = saved.getValue();
        assertTrue(record.isImagesEncrypted());
        assertFalse(record.getImages().contains("lee@example.com"), "플랫폼 DB에 원래 값이 평문으로 남으면 안 된다");
        assertTrue(view.rollbackAvailable());
        assertEquals(1, view.rowChanges().diff().changed());
        assertEquals(List.of("email"), view.rowChanges().maskedColumns());
        RowDiff.Cell email = view.rowChanges().diff().changes().get(0).cells().get(1);
        assertNotEquals("lee@example.com", email.left());
        assertFalse(email.changed());
        assertTrue(view.rowChanges().diff().changes().get(0).cells().get(2).changed());
    }

    @Test
    void 드라이런은_실행권을_잡지_않고_승인_전에도_할_수_있다() {
        ticket("PENDING", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);
        when(operator.executeChange(any(), any())).thenReturn(updated(false));

        ExecutionView view = service.dryRun(7L, false);

        verify(gate, never()).claimExecution(any());
        ArgumentCaptor<ChangePlan> plan = ArgumentCaptor.forClass(ChangePlan.class);
        verify(operator).executeChange(any(), plan.capture());
        assertTrue(plan.getValue().dryRun());
        assertEquals("SELECT * FROM customers WHERE id = 3", plan.getValue().captureSql());
        assertEquals("ROLLED_BACK", view.outcome());
        assertTrue(view.rollbackAvailable(), "드라이런에서는 '실행하면 되돌릴 수 있다'는 예고다");
    }

    @Test
    void 되돌리기_충돌이면_아무것도_쓰지_않고_실행권을_돌려주며_키_값도_마스킹한다() throws Exception {
        when(gate.ticket(7L)).thenReturn(new ChangeTicket(7L, 1L, "UPDATE customers SET grade = 'VIP' WHERE email = 'lee@example.com'",
                null, "EXECUTED", "dev", "admin"));
        RowImage before = image(List.of("email"), List.of(List.of("3", "lee@example.com", "SILVER")));
        RowImage after = image(List.of("email"), List.of(List.of("3", "lee@example.com", "VIP")));
        ChangeExecution executed = new ChangeExecution(7L, 1L, Action.EXECUTE, "UPDATE", "customers", "sha", "admin");
        executed.finish(Outcome.COMMITTED, 1L, null);
        executed.attachImages(cipher.encrypt(JSON.writeValueAsString(new ChangeExecutionService.Images(before, after))),
                true, LocalDateTime.now().plusDays(7), true, null);
        when(executions.findFirstByReviewIdAndActionAndOutcomeOrderByStartedAtDesc(7L, Action.EXECUTE, Outcome.COMMITTED))
                .thenReturn(Optional.of(executed));
        when(gate.claimRollback(7L)).thenReturn(true);
        when(operator.revertChange(eq(writer), any())).thenReturn(new RevertPlan.Outcome(false, 0,
                List.of(new Conflict(List.of("lee@example.com"), List.of("grade"), "실행 뒤 값이 바뀌었다")), 3));

        ExecutionView view = service.revert(7L, false);

        assertEquals("CONFLICT", view.outcome());
        verify(gate).releaseRollback(7L);
        verify(gate, never()).completeRollback(any(), any(), any(), anyLong());
        String detail = view.detail().toString();
        assertFalse(detail.contains("lee@example.com"), detail);
        assertTrue(detail.contains("grade"));
        ArgumentCaptor<RevertPlan> plan = ArgumentCaptor.forClass(RevertPlan.class);
        verify(operator).revertChange(eq(writer), plan.capture());
        assertEquals("SILVER", plan.getValue().before().rows().get(0).get(2), "복호화한 사본이 되돌리기 원본으로 간다");
    }

    @Test
    void 되돌리기가_커밋되면_원래_실행의_되돌리기를_닫고_사본은_비교용으로_남긴다() throws Exception {
        when(gate.ticket(7L)).thenReturn(new ChangeTicket(7L, 1L, "UPDATE customers SET grade = 'VIP' WHERE id = 3",
                null, "EXECUTED", "dev", "admin"));
        ChangeOutcome outcome = updated(true);
        ChangeExecution executed = new ChangeExecution(7L, 1L, Action.EXECUTE, "UPDATE", "customers", "sha", "admin");
        executed.finish(Outcome.COMMITTED, 1L, null);
        executed.attachImages(cipher.encrypt(JSON.writeValueAsString(new ChangeExecutionService.Images(outcome.before(), outcome.after()))),
                true, LocalDateTime.now().plusDays(7), true, null);
        when(executions.findFirstByReviewIdAndActionAndOutcomeOrderByStartedAtDesc(7L, Action.EXECUTE, Outcome.COMMITTED))
                .thenReturn(Optional.of(executed));
        when(gate.claimRollback(7L)).thenReturn(true);
        when(operator.revertChange(eq(writer), any())).thenReturn(new RevertPlan.Outcome(true, 1, List.of(), 4));

        ExecutionView view = service.revert(7L, false);

        assertEquals("COMMITTED", view.outcome());
        verify(gate).completeRollback(7L, 1L, "admin", 1);
        assertFalse(executed.isRollbackAvailable(), "되돌린 실행에 되돌리기 버튼이 다시 뜨면 안 된다");
        assertNotNull(executed.getImages(), "전후 비교 화면·감사용 사본은 보존 기한까지 남는다");
        assertTrue(executed.getRollbackNote().startsWith("되돌렸다"));
    }

    @Test
    void 커밋_불명_정리는_확인_근거가_있어야_하고_실행권에_묶인_티켓만_푼다() {
        ticket("EXECUTING", "UPDATE customers SET grade = 'VIP' WHERE id = 3", null);

        assertEquals(400, assertThrows(WorkbenchRejection.class, () -> service.resolve(7L, true, "확인")).status(),
                "무엇으로 확인했는지 없이 상태를 바꾸지 않는다");
        verify(gate, never()).resolveUncertain(any(), anyBoolean(), any(), any());

        when(gate.resolveUncertain(7L, true, "admin", "root로 id=3 조회, grade=VIP")).thenReturn("EXECUTED");
        ExecutionView view = service.resolve(7L, true, " root로 id=3 조회, grade=VIP ");
        assertEquals("RESOLVE", view.action());
        assertEquals("COMMITTED", view.outcome());
        assertTrue(view.detail().toString().contains("EXECUTED"));

        when(gate.resolveUncertain(eq(7L), anyBoolean(), any(), any())).thenReturn(null);
        assertEquals(409, assertThrows(WorkbenchRejection.class, () -> service.resolve(7L, false, "행이 그대로임")).status());
    }

    @Test
    void DDL_실행에는_생긴_구조만_지우는_역변경을_기종_문법으로_제안하고_잃는_부분은_알린다() throws Exception {
        ticket("EXECUTED", "ALTER TABLE customers ADD COLUMN memo VARCHAR(20)", null);
        SchemaDiffService.SchemaDiff diff = new SchemaDiffService.SchemaDiff("MYSQL", "MYSQL", false, null, List.of(), List.of(),
                List.of(new SchemaDiffService.TableDiff("customers",
                        List.of(new ColumnSchema("memo", "varchar", true, 7)),
                        List.of(new ColumnSchema("legacy", "int", true, 6)),
                        List.of(), List.of(new IndexSchema("idx_memo", List.of("memo"), false)),
                        List.of(), List.of())));
        ChangeExecution executed = new ChangeExecution(7L, 1L, Action.EXECUTE, "DDL", null, "sha", "admin");
        executed.finish(Outcome.COMMITTED, 0L, null);
        executed.attachSchemaDiff(JSON.writeValueAsString(diff));
        when(executions.findByReviewIdOrderByStartedAtDesc(7L)).thenReturn(List.of(executed));
        when(operator.dropIndexStatement("customers", "idx_memo")).thenReturn("DROP INDEX idx_memo ON customers");

        ChangeExecutionService.InverseProposal inverse = service.executions(7L).get(0).inverse();

        assertEquals(List.of("DROP INDEX idx_memo ON customers", "ALTER TABLE customers DROP COLUMN memo"), inverse.statements());
        assertNotNull(inverse.note(), "지워진 열(legacy)은 정의·데이터가 사본에 없어 자동 역변경을 만들지 않았다고 알린다");
    }

    @Test
    void 계획의_숫자만_달라진_것은_계획_변경이_아니다() {
        Probe seq = new Probe("Seq Scan on orders  (cost=0.00..41.00 rows=10 width=4)", List.of(900L, 700L, 800L), null);
        Probe seqAgain = new Probe("Seq Scan on orders  (cost=0.00..45.50 rows=12 width=4)", List.of(1L), null);
        Probe index = new Probe("Index Scan using idx_orders_status on orders  (cost=0.28..8.30 rows=1 width=4)", List.of(50L), null);

        assertFalse(ChangeExecutionService.probeView(seq, seqAgain).planChanged());
        assertTrue(ChangeExecutionService.probeView(seq, index).planChanged());
        assertEquals(800L, ChangeExecutionService.probeView(seq, index).beforeMedianMicros());
    }
}
