package io.dbtower.workbench.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.StatementClassifier.Tier;
import io.dbtower.workbench.internal.WorkbenchAssistant.AssistantRequest;
import io.dbtower.workbench.internal.WorkbenchAssistant.Reply;
import io.dbtower.workbench.internal.WorkbenchAssistant.ResultSample;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.WorksheetService.VersionView;
import io.dbtower.workbench.internal.domain.ChatMessage;
import io.dbtower.workbench.internal.domain.WorkbenchSetting;
import io.dbtower.workbench.internal.domain.Worksheet;
import io.dbtower.workbench.internal.persistence.ChatMessageRepository;
import io.dbtower.workbench.internal.persistence.WorkbenchSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 보조의 계약 — AI는 제안만 하고, 제안은 분류·스키마 대조로 표시되며 워크시트의 새 버전 카드가 되고,
 * 결과 값은 설정이 허용할 때만 AI로 나간다.
 */
class WorkbenchAssistantTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final DbmsOperatorFactory operators = mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = mock(DbmsOperator.class);
    private final AiAnalyzer analyzer = mock(AiAnalyzer.class);
    private final ChatMessageRepository chats = mock(ChatMessageRepository.class);
    private final WorkbenchSettingRepository settings = mock(WorkbenchSettingRepository.class);
    private final WorksheetService worksheets = mock(WorksheetService.class);
    private WorkbenchAssistant assistant;

    private static final SchemaSnapshot SCHEMA = new SchemaSnapshot("MYSQL", "sample", List.of(
            new TableSchema("customers", List.of(new ColumnSchema("id", "int", false, 1),
                    new ColumnSchema("email", "varchar", false, 2)), List.of()),
            new TableSchema("orders", List.of(new ColumnSchema("id", "int", false, 1),
                    new ColumnSchema("status", "varchar", false, 2)), List.of())), false, 200);

    private static DatabaseInstance instance(long id, String name) {
        DatabaseInstance i = new DatabaseInstance(name, DbmsType.MYSQL, "h", 3306, "sample", "monitor", "p");
        ReflectionTestUtils.setField(i, "id", id);
        return i;
    }

    private void worksheet(long worksheetId, long instanceId) {
        Worksheet ws = new Worksheet("unknown", instanceId, "시트");
        ReflectionTestUtils.setField(ws, "id", worksheetId);
        when(worksheets.requireOwned(worksheetId)).thenReturn(ws);
    }

    @BeforeEach
    void setUp() {
        assistant = new WorkbenchAssistant(registry, operators, analyzer, chats, settings, worksheets);
        when(registry.findById(1L)).thenReturn(instance(1, "orders-db"));
        when(registry.findById(2L)).thenReturn(instance(2, "billing-db"));
        worksheet(10, 1);
        worksheet(20, 2);
        when(operators.create(any())).thenReturn(operator);
        when(operator.describeSchema()).thenReturn(SCHEMA);
        when(analyzer.isEnabled()).thenReturn(true);
        when(analyzer.backend()).thenReturn("mock");
        when(settings.findById(anyLong())).thenReturn(Optional.empty());
        when(chats.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(worksheets.addVersion(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new VersionView(1L, 3, "SELECT", "AI", null, null, "제목", "unknown", LocalDateTime.now()));
    }

    private void aiReturns(String json) {
        when(analyzer.complete(eq(CallSite.WORKBENCH), anyString(), anyString())).thenReturn(Optional.of(json));
    }

    private static AssistantRequest ask(String message) {
        return new AssistantRequest(message, List.of(), List.of(), null, null, null);
    }

    @Test
    void 스트리밍은_단계를_흘리지만_저장되는_답은_완성본에서_만든다() {
        String json = "{\"title\": \"VIP 고객\", \"sql\": \"SELECT id FROM customers LIMIT 100\", "
                + "\"explanation\": \"고객 id를 구합니다.\", \"assumptions\": []}";
        when(analyzer.completeStreaming(eq(CallSite.WORKBENCH), anyString(), anyString(), any())).thenAnswer(inv -> {
            Consumer<String> onText = inv.getArgument(3);
            for (int i = 0; i < json.length(); i += 7) {
                onText.accept(json.substring(i, Math.min(json.length(), i + 7)));
            }
            return Optional.of(json);
        });
        List<String> stages = new ArrayList<>();

        Reply reply = assistant.ask(10L, ask("VIP 고객 id"), new WorkbenchAssistant.StreamListener() {
            @Override
            public void stage(String text) {
                stages.add(text);
            }
        });

        assertEquals(2, stages.size(), "스키마 읽기 → AI 질문 두 단계");
        assertEquals("SELECT id FROM customers LIMIT 100", reply.sql());
        assertEquals(Tier.READ, reply.classification().tier());
        assertEquals(3, reply.versionNo());
        verify(analyzer, never()).complete(any(), anyString(), anyString());
    }

    @Test
    void 동기_요청은_스트리밍_경로를_타지_않는다() {
        aiReturns("{\"sql\": \"SELECT 1\", \"explanation\": \"하나\", \"assumptions\": []}");
        assistant.ask(10L, ask("하나"));
        verify(analyzer, never()).completeStreaming(any(), anyString(), anyString(), any());
    }

    @Test
    void 조각_중계는_설명이나_SQL_앞부분이_바뀔_때만_알린다() {
        List<WorkbenchAssistant.Partial> seen = new ArrayList<>();
        WorkbenchAssistant.PartialRelay relay = new WorkbenchAssistant.PartialRelay(new WorkbenchAssistant.StreamListener() {
            @Override
            public void partial(WorkbenchAssistant.Partial partial) {
                seen.add(partial);
            }
        }, 0);

        relay.accept("{\"title\": \"t\", ");
        relay.accept("\"sql\": \"SEL");
        relay.accept("");
        relay.accept("ECT 1\", \"explanation\": \"하나");

        assertEquals(List.of(new WorkbenchAssistant.Partial(null, "SEL"),
                new WorkbenchAssistant.Partial("하나", "SELECT 1")), seen);
    }

    @Test
    void 결과_값_공유가_꺼져_있으면_AI를_부르기_전에_거부한다() {
        ResultSample sample = new ResultSample(List.of("email"), List.of(Arrays.asList((Object) "ho***om")));
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class,
                () -> assistant.ask(10L, new AssistantRequest("요약해줘", List.of(), List.of(), null, null, sample)));
        assertEquals(403, e.status());
        verify(analyzer, never()).complete(any(), anyString(), anyString());
    }

    @Test
    void 결과_값은_설정이_켜진_인스턴스에서만_프롬프트에_들어간다() {
        when(settings.findById(1L)).thenReturn(Optional.of(new WorkbenchSetting(1L, true, "admin")));
        aiReturns("{\"sql\": null, \"explanation\": \"VIP가 1명이다\", \"assumptions\": []}");
        ResultSample sample = new ResultSample(List.of("grade"), List.of(Arrays.asList((Object) "VIP")));

        Reply reply = assistant.ask(10L, new AssistantRequest("등급 분포 요약", List.of(), List.of(), null, null, sample));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(analyzer).complete(eq(CallSite.WORKBENCH), anyString(), user.capture());
        assertTrue(user.getValue().contains("[결과 샘플]"));
        assertTrue(reply.valuesShared());
        assertNull(reply.versionNo(), "SQL이 없는 답변은 버전을 만들지 않는다");
    }

    @Test
    void 제안_SQL은_분류하고_스키마에_없는_테이블을_표시하고_버전_카드가_된다() {
        aiReturns("```json\n{\"sql\": \"SELECT o.id FROM orderz o JOIN customers c ON c.id = o.id\", "
                + "\"title\": \"고객 주문 연결\", \"explanation\": \"주문을 고객과 잇는다\", \"assumptions\": [\"최근 주문\"]}\n```");

        Reply reply = assistant.ask(10L, ask("주문과 고객을 이어줘"));

        assertEquals(Tier.READ, reply.classification().tier());
        assertEquals(List.of("orderz"), reply.unknownTables(), "지어낸 테이블이 사람 눈에 보여야 한다");
        assertEquals(List.of("최근 주문"), reply.assumptions());
        assertEquals(3, reply.versionNo());
        verify(worksheets).addVersion(any(), eq("SELECT o.id FROM orderz o JOIN customers c ON c.id = o.id"),
                eq(WorksheetService.SOURCE_AI), any(), isNull(), eq("고객 주문 연결"));
        verify(operator, never()).executeReadOnly(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void 변경_SQL_제안은_승인_필요로_분류될_뿐_실행되지_않는다() {
        aiReturns("{\"sql\": \"UPDATE customers SET email = NULL WHERE id = 3\", \"explanation\": \"승인 티켓 필요\"}");

        Reply reply = assistant.ask(10L, ask("3번 고객 이메일 지워줘"));

        assertEquals(Tier.NEEDS_APPROVAL, reply.classification().tier());
        verify(operator, never()).executeReadOnly(any(), anyString(), anyInt(), anyInt());
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chats, times(2)).save(saved.capture());
        assertEquals("NEEDS_APPROVAL", saved.getAllValues().get(1).getTier(), "제안의 분류도 대화 기록에 남는다");
    }

    @Test
    void 형식_밖_텍스트는_설명으로만_보여주고_SQL도_버전도_없다() {
        aiReturns("테이블을 찾을 수 없습니다.");
        Reply reply = assistant.ask(10L, ask("재고 테이블 보여줘"));
        assertNull(reply.sql());
        assertNull(reply.classification());
        assertNull(reply.versionNo());
        assertEquals("테이블을 찾을 수 없습니다.", reply.explanation());
        assertNotNull(reply.note());
        verify(worksheets, never()).addVersion(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void 시스템_프롬프트는_워크시트가_달라도_바이트_동일하다() {
        aiReturns("{\"sql\": \"SELECT 1\", \"explanation\": \"-\"}");
        assistant.ask(10L, ask("아무거나"));
        assistant.ask(20L, ask("다른 질문 orders"));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(analyzer, times(2)).complete(eq(CallSite.WORKBENCH), system.capture(), user.capture());
        assertEquals(system.getAllValues().get(0), system.getAllValues().get(1), "휘발성 값이 시스템 프롬프트에 섞이면 캐시가 깨진다");
        assertFalse(system.getAllValues().get(0).contains("billing-db"));
        assertTrue(user.getAllValues().get(1).contains("[질문]"));
    }

    @Test
    void 칩으로_고른_테이블과_컬럼이_프롬프트와_스키마_순서에_반영된다() {
        AssistantRequest req = new AssistantRequest("건수 알려줘", List.of("orders"), List.of("orders.status"), null, null, null);
        String block = WorkbenchAssistant.schemaBlock(SCHEMA, req);
        assertTrue(block.indexOf("orders(") < block.indexOf("customers("), "칩으로 고른 테이블이 먼저 온다: " + block);
        String message = WorkbenchAssistant.userMessage(instance(1, "orders-db"), SCHEMA, req, List.of(), "SELECT 1", false);
        assertTrue(message.contains("[사람이 고른 칩] 테이블 orders, 컬럼 orders.status"));
        assertTrue(message.contains("[현재 편집기 SQL]"));
        assertFalse(message.contains("[결과 샘플]"));
    }

    @Test
    void AI_백엔드가_없으면_호출하지_않고_정직하게_알린다() {
        when(analyzer.isEnabled()).thenReturn(false);
        Reply reply = assistant.ask(10L, ask("질문"));
        assertFalse(reply.aiEnabled());
        verify(analyzer, never()).complete(any(), anyString(), anyString());
        verify(chats, never()).save(any());
    }
}
