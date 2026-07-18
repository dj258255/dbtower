package io.dbtower.alert;

import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.ComparisonService.WindowSummary;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.slo.SloService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인시던트 리포트(B4) 엔드포인트 통합 검증 — 컨트롤러 → 보안(ADMIN) → 조립기(IncidentReportService)
 * → 마크다운 → JSON 직렬화까지 스프링 컨텍스트로 관통한다. 조립기의 실제 조립·절단·정직 표기 로직을
 * 그대로 태우고, 신호 원천만(스냅샷 테이블 직결 서비스) 목으로 빈 결과를 넣는다.
 *
 * 스냅샷 테이블(query_snapshot·config_param_change·wait_event·health_sample)은 JPA 엔티티가 없어
 * H2(create-drop, Flyway off) 테스트 DB에 존재하지 않는다. 이 저장소는 그 조회를 목으로 유닛 검증하는
 * 방침이라(ComparisonServiceTest 등), 여기서도 원천 서비스만 목으로 대체하고 나머지는 실빈으로 둔다.
 * 목 유닛(IncidentReportServiceTest)이 조립 로직을 검증하는 것과 달리, 여기서는 라우팅·보안·파라미터
 * 바인딩(ISO 구간)·직렬화 계약을 검증한다. publish=false로 웹훅은 타지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentReportIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository repository;

    // 스냅샷 테이블 직결 원천 — H2에 테이블이 없어 목으로 빈 신호를 넣는다(조립기는 실빈)
    @MockitoBean
    ComparisonService comparisonService;
    @MockitoBean
    ConfigDriftService configDriftService;
    @MockitoBean
    WaitEventHistoryService waitHistory;
    @MockitoBean
    SloService sloService;

    private Long instanceId;

    @BeforeEach
    void register() {
        // register()는 실접속 헬스체크를 요구하므로 우회하고 리포지토리로 직접 넣는다(대상 DB 불필요)
        DatabaseInstance saved = repository.save(new DatabaseInstance(
                "incident-it", DbmsType.POSTGRESQL, "localhost", 1, "app", "u", "p"));
        this.instanceId = saved.getId();

        WindowSummary w = new WindowSummary(100, 50.0, 0.5, 200, 3);
        when(comparisonService.compare(anyLong(), any(), any(), any(), any()))
                .thenReturn(new CompareResult(w, w, 10.0, 20.0, 5.0, 1, List.of()));
        when(configDriftService.changesInWindow(anyLong(), any(), any())).thenReturn(List.of());
        when(waitHistory.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(sloService.healthInWindow(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        repository.deleteById(instanceId);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 신호가_없어도_리포트가_생성되고_각_절은_없음이다() throws Exception {
        mvc.perform(post("/api/instances/" + instanceId + "/incident-report").with(csrf())
                        .param("from", "2026-07-18T03:00:00")
                        .param("to", "2026-07-18T05:00:00")
                        .param("publish", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(instanceId))
                .andExpect(jsonPath("$.instanceName").value("incident-it"))
                .andExpect(jsonPath("$.from").value("2026-07-18 03:00"))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("# 인시던트 리포트")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## 설정 변경 (0)")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## 재구성 한계")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 구간이_24시간을_넘으면_잘라내고_노트를_남긴다() throws Exception {
        mvc.perform(post("/api/instances/" + instanceId + "/incident-report").with(csrf())
                        .param("from", "2026-07-18T00:00:00")
                        .param("to", "2026-07-20T00:00:00") // 48시간
                        .param("publish", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncationNotes", org.hamcrest.Matchers.hasSize(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.to").value("2026-07-19 00:00")); // from + 24h
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_인시던트_리포트를_생성할_수_없다() throws Exception {
        mvc.perform(post("/api/instances/" + instanceId + "/incident-report").with(csrf())
                        .param("from", "2026-07-18T03:00:00")
                        .param("to", "2026-07-18T05:00:00")
                        .param("publish", "false"))
                .andExpect(status().isForbidden());
    }
}
