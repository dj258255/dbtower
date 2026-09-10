package io.dbtower.workbench.internal;

import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.WorksheetService.TimelineItem;
import io.dbtower.workbench.internal.WorksheetService.VersionView;
import io.dbtower.workbench.internal.domain.ChatMessage;
import io.dbtower.workbench.internal.domain.SqlVersion;
import io.dbtower.workbench.internal.domain.Worksheet;
import io.dbtower.workbench.internal.persistence.ChatMessageRepository;
import io.dbtower.workbench.internal.persistence.SqlVersionRepository;
import io.dbtower.workbench.internal.persistence.WorksheetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 워크시트의 계약 — 만든 사람만 보고, 버전은 덮어쓰지 않고 쌓이며, 같은 쿼리 재실행은 이력을 도배하지 않는다. */
class WorksheetServiceTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final WorksheetRepository worksheets = mock(WorksheetRepository.class);
    private final SqlVersionRepository versions = mock(SqlVersionRepository.class);
    private final ChatMessageRepository chats = mock(ChatMessageRepository.class);
    private final WorksheetService service = new WorksheetService(registry, worksheets, versions, chats);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("kim", null, "ROLE_VIEWER"));
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(worksheets.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private Worksheet worksheet(long id, String owner, long instanceId) {
        Worksheet ws = new Worksheet(owner, instanceId, "결제 실패 분석");
        ReflectionTestUtils.setField(ws, "id", id);
        when(worksheets.findById(id)).thenReturn(Optional.of(ws));
        return ws;
    }

    private SqlVersion version(long worksheetId, int no, String sql, String source) {
        return new SqlVersion(worksheetId, no, sql, source, null, null, null, "kim");
    }

    @Test
    void 다른_사용자의_워크시트는_존재하지_않는_것처럼_404다() {
        worksheet(10, "lee", 1);
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.timeline(10L));
        assertEquals(404, e.status());
    }

    @Test
    void 다른_인스턴스의_워크시트에는_실행_버전을_끼워_넣지_못한다() {
        worksheet(10, "kim", 1);
        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.recordRun(10L, 2L, "SELECT 1"));
        assertEquals(404, e.status());
        verify(versions, never()).save(any());
    }

    @Test
    void 되돌리기는_과거를_덮지_않고_새_버전으로_쌓인다() {
        Worksheet ws = worksheet(10, "kim", 1);
        when(versions.findByWorksheetIdAndVersionNo(10L, 1)).thenReturn(Optional.of(version(10, 1, "SELECT a FROM t", "AI")));
        when(versions.findTopByWorksheetIdOrderByVersionNoDesc(10L)).thenReturn(Optional.of(version(10, 2, "SELECT b FROM t", "RUN")));

        VersionView restored = service.restore(10L, 1);

        assertEquals(3, restored.versionNo(), "v1로 돌아가도 번호는 v3 — v1·v2는 그대로 남는다");
        assertEquals("RESTORE", restored.source());
        assertEquals(1, restored.restoredFrom());
        assertEquals("v1로 되돌림", restored.title());
        assertEquals("kim", restored.principal(), "카드에 되돌린 사람이 붙는다");
        assertEquals("SELECT a FROM t", restored.sql());
        assertEquals("SELECT a FROM t", ws.getCurrentSql(), "되돌린 버전이 편집기 내용이 된다");
    }

    @Test
    void 같은_쿼리를_다시_실행하면_버전을_만들지_않는다() {
        worksheet(10, "kim", 1);
        when(versions.findTopByWorksheetIdOrderByVersionNoDesc(10L)).thenReturn(Optional.of(version(10, 4, "SELECT 1", "RUN")));

        assertTrue(service.recordRun(10L, 1L, "  SELECT 1  ").isEmpty());
        verify(versions, never()).save(any());
    }

    @Test
    void AI_제안_버전은_편집기_내용을_바꾸지_않는다() {
        Worksheet ws = worksheet(10, "kim", 1);
        ws.updateSql("SELECT 사람이_쓰던_것");
        when(versions.findTopByWorksheetIdOrderByVersionNoDesc(10L)).thenReturn(Optional.empty());

        VersionView v = service.addVersion(ws, "SELECT ai_제안", WorksheetService.SOURCE_AI, 77L, null, "제안");

        assertEquals(1, v.versionNo());
        assertEquals("SELECT 사람이_쓰던_것", ws.getCurrentSql(), "사람이 편집기로 가져가기 전까지는 제안일 뿐이다");
    }

    @Test
    void 타임라인은_대화와_실행_버전을_시간순으로_섞고_AI_버전은_답변_카드에_붙인다() {
        worksheet(10, "kim", 1);
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 10, 12, 0);
        ChatMessage question = new ChatMessage(10L, "kim", 1L, ChatMessage.USER, "결제 실패 건수", null, null, null, null, false);
        ChatMessage answer = new ChatMessage(10L, "kim", 1L, ChatMessage.ASSISTANT, "상태별로 센다", "SELECT status FROM orders",
                "READ", "SELECT", null, false);
        ReflectionTestUtils.setField(question, "createdAt", t0);
        ReflectionTestUtils.setField(answer, "id", 55L);
        ReflectionTestUtils.setField(answer, "createdAt", t0.plusSeconds(20));
        SqlVersion ai = new SqlVersion(10L, 1, "SELECT status FROM orders", "AI", 55L, null, "상태별 건수", "kim");
        ReflectionTestUtils.setField(ai, "createdAt", t0.plusSeconds(20));
        SqlVersion run = new SqlVersion(10L, 2, "SELECT status, COUNT(*) FROM orders GROUP BY status", "RUN", null, null, "직접 실행한 SQL", "kim");
        ReflectionTestUtils.setField(run, "createdAt", t0.plusSeconds(60));
        when(chats.findByWorksheetIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(question, answer));
        when(versions.findByWorksheetIdOrderByVersionNoAsc(10L)).thenReturn(List.of(ai, run));

        List<TimelineItem> items = service.timeline(10L);

        assertEquals(List.of("MESSAGE", "MESSAGE", "VERSION"), items.stream().map(TimelineItem::type).toList());
        assertEquals(1, items.get(1).message().version().versionNo(), "AI 답변 카드에 v1 체크포인트가 붙는다");
        assertEquals("상태별 건수", items.get(1).message().version().title());
        assertEquals(2, items.get(2).version().versionNo());
    }

    @Test
    void 제목을_안_주면_번호를_붙여_만든다() {
        when(worksheets.countByPrincipalAndInstanceId("kim", 1L)).thenReturn(2L);
        ArgumentCaptor<Worksheet> saved = ArgumentCaptor.forClass(Worksheet.class);
        service.create(1L, " ");
        verify(worksheets).save(saved.capture());
        assertEquals("워크시트 3", saved.getValue().getTitle());
        assertEquals("kim", saved.getValue().getPrincipal());
    }
}
