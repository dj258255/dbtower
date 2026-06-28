package io.dbtower.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 감사 로그(A6) 통합 검증 — "무엇이 기록되고 무엇이 기록되지 않나"를 테스트로 고정한다.
 * 기록: /api/** 의 POST/PUT/DELETE(성공·실패·403 월권 시도). 비기록: GET 조회.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 상태_변경_요청은_감사_행이_남는다() throws Exception {
        // 미등록 인스턴스라 백업은 FAILED로 저장되지만 요청 자체는 200 — 외부 접속 없이 핸들러까지 도달한다
        mvc.perform(post("/api/instances/999/backup").with(csrf()))
                .andExpect(status().isOk());

        List<AuditEvent> events = repository.findAll();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo("POST /api/instances/999/backup");
        assertThat(event.getPrincipal()).isEqualTo("user");
        assertThat(event.getRole()).isEqualTo("ADMIN");
        assertThat(event.getInstanceId()).isEqualTo(999L);
        assertThat(event.getOutcome()).isEqualTo(200);
        assertThat(event.getDurationMs()).isNotNull();
        assertThat(event.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 검증에_실패한_등록_시도도_400으로_기록된다() throws Exception {
        // 감사는 결과가 아니라 시도의 기록 — 실패한 상태 변경 시도가 빠지면 이상 징후를 놓친다
        mvc.perform(post("/api/instances").with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        List<AuditEvent> events = repository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("POST /api/instances");
        assertThat(events.get(0).getOutcome()).isEqualTo(400);
        assertThat(events.get(0).getInstanceId()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void GET_조회는_기록하지_않는다() throws Exception {
        mvc.perform(get("/api/instances")).andExpect(status().isOk());

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER의_월권_시도는_403으로_기록된다() throws Exception {
        // 인가 거부는 시큐리티 필터에서 끝나 인터셉터에 안 잡힌다 — AuthorizationAuditListener 경로 검증
        mvc.perform(post("/api/instances").with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        List<AuditEvent> events = repository.findAll();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo("POST /api/instances");
        assertThat(event.getPrincipal()).isEqualTo("user");
        assertThat(event.getRole()).isEqualTo("VIEWER");
        assertThat(event.getOutcome()).isEqualTo(403);
        assertThat(event.getDurationMs()).isNull();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 감사_로그_조회는_VIEWER에게_금지된다() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 감사_로그는_최신순으로_limit_만큼_반환한다() throws Exception {
        LocalDateTime base = LocalDateTime.now();
        repository.save(new AuditEvent(base.minusMinutes(2), "admin", "ADMIN", "POST /api/first", null, 201, 3L));
        repository.save(new AuditEvent(base.minusMinutes(1), "admin", "ADMIN", "POST /api/second", null, 200, 5L));
        repository.save(new AuditEvent(base, "admin", "ADMIN", "DELETE /api/instances/7", 7L, 204, 4L));

        mvc.perform(get("/api/audit").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("DELETE /api/instances/7"))
                .andExpect(jsonPath("$[0].instanceId").value(7))
                .andExpect(jsonPath("$[1].action").value("POST /api/second"));
    }
}
