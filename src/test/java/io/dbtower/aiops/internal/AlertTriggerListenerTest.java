package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.alert.AlertRaisedEvent;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertTriggerListenerTest {

    private final AiOperationService service = mock(AiOperationService.class);
    private final RegistryService registry = mock(RegistryService.class);
    private final AiOperationChannels channels = mock(AiOperationChannels.class);
    private final AlertRaisedEvent regression = new AlertRaisedEvent(AlertRaisedEvent.Source.REGRESSION, 2L, "mysql-a",
            List.of("레이턴시 회귀: SELECT ... (+304%)"), 20, LocalDateTime.of(2026, 9, 16, 3, 30, 41));

    @Test
    void 꺼져_있으면_아무것도_접수하지_않는다() {
        new AlertTriggerListener(service, registry, false, channels).onAlert(regression);
        verify(service, never()).submit(any());
    }

    @Test
    void 경보_출처로_유형을_고르고_담당_팀의_채널로_접수한다() {
        DatabaseInstance instance = mock(DatabaseInstance.class);
        when(instance.getTeamLabel()).thenReturn("team-a");
        when(registry.findOptional(2L)).thenReturn(Optional.of(instance));
        when(channels.forTeam("team-a")).thenReturn("C-ALERTS");

        new AlertTriggerListener(service, registry, true, channels).onAlert(regression);

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service).submit(captor.capture());
        AiOperationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(AiOperationType.REGRESSION_EXPLANATION);
        assertThat(request.trigger()).isEqualTo(AiOperationTrigger.ALERT);
        assertThat(request.requester()).isEqualTo("alert:mysql-a");
        assertThat(request.team()).isEqualTo("team-a");
        assertThat(request.windowMinutes()).isEqualTo(20);
        assertThat(request.requestId()).isEqualTo("alert:REGRESSION:2:2026-09-16T03:30");
        assertThat(request.replyChannel()).isEqualTo("C-ALERTS");
    }

    @Test
    void 접수가_실패해도_경보_스레드로_예외를_올리지_않는다() {
        when(registry.findOptional(2L)).thenReturn(Optional.empty());
        when(service.submit(any())).thenThrow(new IllegalStateException("진행 중인 AI 작업이 3건입니다"));
        AlertRaisedEvent ops = new AlertRaisedEvent(AlertRaisedEvent.Source.OPERATIONS, 2L, "mysql-a",
                List.of("백업 없음"), 60, LocalDateTime.now());
        assertThatCode(() -> new AlertTriggerListener(service, registry, true, channels).onAlert(ops)).doesNotThrowAnyException();
    }
}
