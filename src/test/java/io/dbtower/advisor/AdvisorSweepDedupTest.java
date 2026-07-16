package io.dbtower.advisor;

import io.dbtower.advisor.internal.job.AdvisorSweepJob;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * 서버 공유 인지 (Phase 4) — 호스트 스코프 Advisor의 스윕 dedup 검증.
 * 디스크 예측처럼 판정 대상이 호스트 자원인 점검은, 같은 호스트(+같은 nodeFilter)를 공유하는
 * 인스턴스들에 그룹당 1회만 실행되고 나머지는 SHARED로 표기된다(중복 탐침·중복 지적 방지).
 * 온디맨드 단건 경로는 dedup하지 않는다 — 위험 귀속(헬스 스코어)까지 반으로 줄이면 왜곡이다.
 */
class AdvisorSweepDedupTest {

    private final DatabaseInstanceRepository repository = Mockito.mock(DatabaseInstanceRepository.class);
    private final AdvisorService advisorService = Mockito.mock(AdvisorService.class);

    /** 호스트 스코프 Advisor 스텁 — 디스크 예측과 같은 성격 */
    private Advisor hostAdvisor(String id) {
        Advisor a = Mockito.mock(Advisor.class);
        when(a.id()).thenReturn(id);
        when(a.title()).thenReturn(id);
        when(a.hostScoped()).thenReturn(true);
        when(a.supports(any())).thenReturn(true);
        return a;
    }

    private DatabaseInstance instanceOn(long id, String name, String host, String nodeFilter) {
        DatabaseInstance m = Mockito.mock(DatabaseInstance.class);
        when(m.getId()).thenReturn(id);
        when(m.getName()).thenReturn(name);
        when(m.getHost()).thenReturn(host);
        when(m.getNodeFilter()).thenReturn(nodeFilter);
        return m;
    }

    private void stubReport() {
        when(advisorService.inspect(any(DatabaseInstance.class), anyMap())).thenAnswer(inv -> {
            DatabaseInstance i = inv.getArgument(0);
            return InstanceAdvisorReport.of(i.getId(), i.getName(), DbmsType.POSTGRESQL,
                    LocalDateTime.now(), List.of());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void 같은_호스트를_공유하면_호스트_스코프_점검은_대표만_실행한다() {
        // 같은 머신의 PG와 MySQL — 포트는 달라도 디스크는 같다(포트는 키가 아니다)
        DatabaseInstance pg = instanceOn(1, "pg-app", "10.0.0.5", null);
        DatabaseInstance mysql = instanceOn(2, "mysql-app", "10.0.0.5", null);
        when(repository.findAll()).thenReturn(List.of(pg, mysql));
        stubReport();

        new AdvisorSweepJob(repository, advisorService, List.of(hostAdvisor("disk-forecast"))).sweep();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(advisorService, times(2)).inspect(any(DatabaseInstance.class), captor.capture());
        // 대표(id 최소)는 전부 실행, 두 번째는 disk-forecast가 대표 이름으로 SHARED 처리
        assertTrue(captor.getAllValues().get(0).isEmpty());
        assertEquals(Map.of("disk-forecast", "pg-app"), captor.getAllValues().get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodeFilter가_다르면_다른_점검이라_dedup하지_않는다() {
        // 같은 호스트라도 보는 마운트가 다르면(nodeFilter) 쿼리가 다르다 — 생략하면 그 마운트는 사각
        DatabaseInstance root = instanceOn(1, "pg-app", "10.0.0.5", null);
        DatabaseInstance data = instanceOn(2, "mysql-app", "10.0.0.5", "mountpoint=\"/data\"");
        when(repository.findAll()).thenReturn(List.of(root, data));
        stubReport();

        new AdvisorSweepJob(repository, advisorService, List.of(hostAdvisor("disk-forecast"))).sweep();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(advisorService, times(2)).inspect(any(DatabaseInstance.class), captor.capture());
        assertTrue(captor.getAllValues().get(0).isEmpty());
        assertTrue(captor.getAllValues().get(1).isEmpty());
    }

    @Test
    void SHARED_체크는_대표_인스턴스_이름을_남긴다() {
        // AdvisorService 실물 경로 — sharedBy에 있는 Advisor는 실행하지 않고 SHARED로 표기
        Advisor advisor = hostAdvisor("disk-forecast");
        AdvisorService real = new AdvisorService(List.of(advisor), null, null);
        DatabaseInstance i = instanceOn(2, "mysql-app", "10.0.0.5", null);
        when(i.getType()).thenReturn(DbmsType.MYSQL);

        InstanceAdvisorReport report = real.inspect(i, Map.of("disk-forecast", "pg-app"));

        assertEquals(1, report.checks().size());
        AdvisorCheck check = report.checks().get(0);
        assertEquals(AdvisorCheck.Status.SHARED, check.status());
        assertTrue(check.note().contains("pg-app"));
        verify(advisor, never()).inspect(any(), any(DbmsOperator.class));
    }
}
