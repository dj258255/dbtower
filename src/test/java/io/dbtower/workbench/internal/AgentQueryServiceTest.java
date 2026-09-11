package io.dbtower.workbench.internal;

import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.domain.WorkbenchSetting;
import io.dbtower.workbench.internal.persistence.WorkbenchSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** MCP 에이전트 조회 — 결과 값 공유가 꺼진 인스턴스는 대상 DB에 닿기 전에 막고, 켜져도 행 상한을 낮춰 사람의 조회 경로로 보낸다. */
class AgentQueryServiceTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final WorkbenchSettingRepository settings = mock(WorkbenchSettingRepository.class);
    private final WorkbenchService workbench = mock(WorkbenchService.class);
    private final AgentQueryService service = new AgentQueryService(registry, settings, workbench);

    @Test
    void 결과_값_공유가_꺼진_인스턴스는_대상_DB에_닿기_전에_403이다() {
        when(settings.findById(1L)).thenReturn(Optional.empty());

        WorkbenchRejection e = assertThrows(WorkbenchRejection.class, () -> service.query(1L, "SELECT * FROM customers", null));

        assertEquals(403, e.status());
        verify(workbench, never()).runAs(any(), any(), anyInt(), any());
    }

    @Test
    void 켜진_인스턴스는_사람의_조회_경로로_가되_행_상한을_낮추고_기록을_구분한다() {
        when(settings.findById(1L)).thenReturn(Optional.of(new WorkbenchSetting(1L, true, "admin")));

        service.query(1L, "SELECT * FROM customers", 1_000);

        verify(workbench).runAs(1L, "SELECT * FROM customers", AgentQueryService.AGENT_ROW_LIMIT, "AGENT_QUERY");
    }
}
