package io.dbtower.alert;

import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.alert.internal.ConfigDriftService.DriftResult;
import io.dbtower.alert.internal.persistence.ConfigDriftDao;
import io.dbtower.alert.internal.persistence.ConfigDriftDao.ParamChangeRow;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 설정 드리프트 판정 검증 — 첫 수집은 기준선(경보 없음), 무변경은 스냅샷 1행만,
 * 변경은 로그+거울 갱신, 노이즈 파라미터는 변경으로 잡지 않는다.
 */
class ConfigDriftServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final ConfigDriftDao dao = Mockito.mock(ConfigDriftDao.class);
    private final ConfigDriftService service = new ConfigDriftService(registryService, operatorFactory, dao);

    private final DatabaseInstance instance = Mockito.mock(DatabaseInstance.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 12, 0);

    private void stub(List<DbParameter> params, Map<String, String> mirror) {
        when(instance.getId()).thenReturn(7L);
        when(operatorFactory.create(instance)).thenReturn(operator);
        when(operator.parameters()).thenReturn(params);
        when(dao.currentParams(7L)).thenReturn(mirror);
        when(dao.insertSnapshot(anyLong(), any(), anyString(), anyInt(), anyBoolean())).thenReturn(1L);
    }

    private static DbParameter p(String name, String value) {
        return new DbParameter(name, value, null);
    }

    @Test
    void 첫_수집은_기준선이고_경보를_내지_않는다() {
        stub(List.of(p("max_connections", "100")), Map.of()); // 거울 비어 있음

        DriftResult result = service.collect(instance, NOW);

        assertTrue(result.baseline());
        assertFalse(result.changed());
        verify(dao).insertBaseline(eq(7L), anyMap());
        verify(dao).insertSnapshot(eq(7L), eq(NOW), anyString(), eq(0), eq(true));
        verify(dao, never()).insertChanges(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    void 무변경이면_스냅샷만_남기고_경보없음() {
        stub(List.of(p("max_connections", "100"), p("work_mem", "4MB")),
                Map.of("max_connections", "100", "work_mem", "4MB"));

        DriftResult result = service.collect(instance, NOW);

        assertFalse(result.changed());
        assertFalse(result.baseline());
        verify(dao).insertSnapshot(eq(7L), eq(NOW), anyString(), eq(0), eq(false));
        verify(dao, never()).insertChanges(anyLong(), anyLong(), any(), anyList());
        verify(dao, never()).applyDeltas(anyLong(), anyList());
    }

    @Test
    void 값이_바뀌면_변경으로_잡고_거울을_갱신한다() {
        stub(List.of(p("work_mem", "8MB"), p("max_connections", "100")),
                Map.of("work_mem", "4MB", "max_connections", "100"));

        DriftResult result = service.collect(instance, NOW);

        assertTrue(result.changed());
        assertEquals(1, result.changes().size());
        ParamChangeRow c = result.changes().get(0);
        assertEquals("work_mem", c.paramName());
        assertEquals("4MB", c.oldValue());
        assertEquals("8MB", c.newValue());
        assertEquals("CHANGED", c.kind());
        verify(dao).insertChanges(eq(1L), eq(7L), eq(NOW), anyList());
        verify(dao).applyDeltas(eq(7L), anyList());
    }

    @Test
    void 추가와_제거를_구분한다() {
        stub(List.of(p("new_param", "on")),                 // work_mem 사라지고 new_param 생김
                Map.of("work_mem", "4MB"));

        DriftResult result = service.collect(instance, NOW);

        assertTrue(result.changed());
        assertEquals(2, result.changes().size());
        assertTrue(result.changes().stream().anyMatch(c -> c.kind().equals("ADDED") && c.paramName().equals("new_param")));
        assertTrue(result.changes().stream().anyMatch(c -> c.kind().equals("REMOVED") && c.paramName().equals("work_mem")));
    }

    @Test
    void 노이즈_파라미터_변경은_무시한다() {
        // application_name만 바뀜 — 노이즈라 변경으로 잡히지 않는다
        stub(List.of(p("application_name", "psql-2"), p("max_connections", "100")),
                Map.of("max_connections", "100"));
        // 거울에 application_name이 없어도, fresh에서 필터되므로 ADDED로도 안 잡힌다

        DriftResult result = service.collect(instance, NOW);

        assertFalse(result.changed());
    }
}
