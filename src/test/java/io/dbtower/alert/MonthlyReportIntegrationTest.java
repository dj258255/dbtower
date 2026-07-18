package io.dbtower.alert;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 월간 점검 리포트(B5) 엔드포인트 통합 검증 — 컨트롤러 → 조립기(MonthlyReportService) → 마크다운 →
 * JSON까지 스프링 컨텍스트로 관통한다. 대상 DB에 붙는 콜라보레이터(AdvisorService)만 목으로
 * 대체하고, 나머지(헬스 스코어·백업 신선도·FinOps·설정 변경)는 메타 DB 실경로로 둔다 — 라우팅·
 * 보안(ADMIN)·마크다운 조립·직렬화 계약을 검증하는 데 초점.
 *
 * ScoreQuery는 목으로 대체하지 않는다: 인터페이스라 구현체(ScoreService)를 구체 타입으로 주입받는
 * 빈(ScoreController)과 충돌한다. 실빈은 헬스 프로브 실패를 collect로 격리해 던지지 않으므로
 * 점수는 값 대신 존재만 검증한다(값 자체는 ScoreService 유닛의 몫).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyReportIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository repository;

    @MockitoBean
    AdvisorService advisorService; // 대상 DB에 붙어 접속 시도를 하므로 목으로 격리(내부는 자체 유닛이 검증)

    // config_param_change는 JPA 엔티티가 없어 H2에 없다 — 유일하게 격리되지 않은 스냅샷 조회라 목으로 대체
    @MockitoBean
    ConfigDriftService configDriftService;

    private Long instanceId;

    @BeforeEach
    void register() {
        DatabaseInstance saved = repository.save(new DatabaseInstance(
                "monthly-it", DbmsType.POSTGRESQL, "localhost", 1, "app", "u", "p"));
        this.instanceId = saved.getId();
        // ScoreService.evaluate도 내부에서 advisorService.inspect를 부르므로 목 하나가 양쪽을 덮는다
        when(advisorService.inspect(any(DatabaseInstance.class)))
                .thenReturn(InstanceAdvisorReport.of(instanceId, "monthly-it", DbmsType.POSTGRESQL,
                        LocalDateTime.now(), List.of()));
        when(configDriftService.changesInWindow(anyLong(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        repository.deleteById(instanceId);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 월간_리포트가_생성되고_점수와_각_절을_담는다() throws Exception {
        mvc.perform(post("/api/instances/" + instanceId + "/monthly-report").with(csrf())
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(instanceId))
                .andExpect(jsonPath("$.instanceName").value("monthly-it"))
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.grade").isNotEmpty())
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("# 월간 점검 리포트")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## 헬스 스코어")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## 백업")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## Advisor 점검")))
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("## 설정 변경")));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_월간_리포트를_생성할_수_없다() throws Exception {
        mvc.perform(post("/api/instances/" + instanceId + "/monthly-report").with(csrf())
                        .param("days", "30"))
                .andExpect(status().isForbidden());
    }
}
