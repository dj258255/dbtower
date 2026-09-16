package io.dbtower.aiops.internal.job;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.aiops.internal.AiOperationChannels;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeriodicReportJobTest {

    /** 수요일(2026-09-16) — 기준일이 그 주 월요일(09-14)로 내려가는지 함께 본다 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T03:00:00Z"), ZoneOffset.UTC);

    private final AiOperationService service = mock(AiOperationService.class);
    private final RegistryService registry = mock(RegistryService.class);
    private final AiOperationChannels channels = new AiOperationChannels("C-DEFAULT", "team-a=C111,team-b=C222");

    @Test
    void 꺼져_있으면_아무것도_접수하지_않는다() {
        PeriodicReportJob job = new PeriodicReportJob(service, registry, channels, false, CLOCK);

        job.run();

        verify(service, never()).submit(any());
    }

    @Test
    void 팀마다_한_건씩_접수하고_팀_없는_인스턴스는_한_묶음으로_친다() {
        List<DatabaseInstance> instances = instances("team-a", "team-a", "team-b", null);
        when(registry.findAll()).thenReturn(instances);
        PeriodicReportJob job = new PeriodicReportJob(service, registry, channels, true, CLOCK);

        job.run();

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service, times(3)).submit(captor.capture());
        List<AiOperationRequest> requests = captor.getAllValues();
        assertThat(requests).extracting(AiOperationRequest::team).containsExactly(null, "team-a", "team-b");

        AiOperationRequest noTeam = requests.get(0);
        assertThat(noTeam.type()).isEqualTo(AiOperationType.PERIODIC_REPORT);
        assertThat(noTeam.trigger()).isEqualTo(AiOperationTrigger.SCHEDULE);
        assertThat(noTeam.instanceId()).isNull();
        assertThat(noTeam.windowMinutes()).isEqualTo(10080);
        assertThat(noTeam.requester()).isEqualTo("schedule:weekly-report");
        assertThat(noTeam.requestId()).isEqualTo("periodic:-:2026-09-14");
        assertThat(noTeam.replyChannel()).isEqualTo("C-DEFAULT");
        assertThat(noTeam.prompt()).isEqualTo("지난 7일 운영 상태를 요약해줘. 팀: 전체");

        AiOperationRequest teamA = requests.get(1);
        assertThat(teamA.requester()).isEqualTo("schedule:weekly-report:team-a");
        assertThat(teamA.requestId()).isEqualTo("periodic:team-a:2026-09-14");
        assertThat(teamA.replyChannel()).isEqualTo("C111");
        assertThat(teamA.prompt()).isEqualTo("지난 7일 운영 상태를 요약해줘. 팀: team-a");

        assertThat(requests.get(2).requestId()).isEqualTo("periodic:team-b:2026-09-14");
        assertThat(requests.get(2).replyChannel()).isEqualTo("C222");
    }

    @Test
    void 표에_없는_팀은_채널_없이_접수한다() {
        List<DatabaseInstance> instances = instances("team-z");
        when(registry.findAll()).thenReturn(instances);
        PeriodicReportJob job = new PeriodicReportJob(service, registry, channels, true, CLOCK);

        job.run();

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service).submit(captor.capture());
        assertThat(captor.getValue().replyChannel()).isNull();
    }

    @Test
    void 같은_주에_두_번_돌아도_멱등_키가_같다() {
        List<DatabaseInstance> instances = instances("team-a");
        when(registry.findAll()).thenReturn(instances);
        PeriodicReportJob job = new PeriodicReportJob(service, registry, channels, true, CLOCK);

        job.run();
        job.run();

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service, times(2)).submit(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiOperationRequest::requestId)
                .containsExactly("periodic:team-a:2026-09-14", "periodic:team-a:2026-09-14");
    }

    @Test
    void 한_팀_접수가_실패해도_나머지_팀은_접수한다() {
        List<DatabaseInstance> instances = instances("team-a", "team-b");
        when(registry.findAll()).thenReturn(instances);
        when(service.submit(any())).thenAnswer(invocation -> {
            AiOperationRequest request = invocation.getArgument(0);
            if ("team-a".equals(request.team())) {
                throw new IllegalStateException("진행 중인 AI 작업이 3건입니다");
            }
            return null;
        });
        PeriodicReportJob job = new PeriodicReportJob(service, registry, channels, true, CLOCK);

        job.run();

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service, times(2)).submit(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiOperationRequest::team).containsExactly("team-a", "team-b");
    }

    /** 목을 먼저 만들어 두고 스텁한다 — thenReturn 인자 안에서 when()을 겹치면 UnfinishedStubbing이 된다 */
    private static List<DatabaseInstance> instances(String... teamLabels) {
        List<DatabaseInstance> instances = new ArrayList<>();
        for (String teamLabel : teamLabels) {
            DatabaseInstance instance = mock(DatabaseInstance.class);
            when(instance.getTeamLabel()).thenReturn(teamLabel);
            instances.add(instance);
        }
        return instances;
    }
}
