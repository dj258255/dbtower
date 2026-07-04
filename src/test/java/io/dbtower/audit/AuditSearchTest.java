package io.dbtower.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 감사 로그 검색(Specification) 검증 — 동적 필터가 조합에 따라 WHERE를 좁히는지 고정한다.
 * 여기가 Specification이 정적 쿼리보다 나은 지점의 증명: 필터 6종의 임의 조합을 메서드 폭발 없이 처리.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditSearchTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditEventRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 10, 0);
        // alice가 인스턴스 8에 explain(200), bob이 등록 시도 거부(403), alice 로그인 실패(401)
        repository.save(new AuditEvent(base, "alice", "ADMIN", "POST /api/instances/8/explain", 8L, 200, 145L));
        repository.save(new AuditEvent(base.plusHours(1), "bob", "VIEWER", "POST /api/instances", null, 403, null));
        repository.save(new AuditEvent(base.plusHours(2), "alice", null, "LOGIN", null, 401, null));
        repository.save(new AuditEvent(base.plusDays(30), "alice", "ADMIN", "DELETE /api/instances/3", 3L, 200, 12L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 필터가_없으면_전체를_최신순으로_돌려준다() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                // 최신순: base+30일(DELETE)이 맨 앞
                .andExpect(jsonPath("$[0].action").value("DELETE /api/instances/3"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void principal로_좁힌다() throws Exception {
        mvc.perform(get("/api/audit").param("principal", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void outcome로_월권_시도만_뽑는다() throws Exception {
        mvc.perform(get("/api/audit").param("outcome", "403"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].principal").value("bob"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 필터_조합은_AND로_좁힌다() throws Exception {
        // alice + 200 -> explain(8)과 delete(3) 두 건 (로그인 실패 401은 제외)
        mvc.perform(get("/api/audit").param("principal", "alice").param("outcome", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void instanceId와_action_부분일치를_함께_건다() throws Exception {
        mvc.perform(get("/api/audit").param("instanceId", "8").param("action", "explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].instanceId").value(8));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기간으로_좁힌다() throws Exception {
        // base ~ base+3시간: 첫 3건만(30일 뒤 DELETE 제외)
        mvc.perform(get("/api/audit")
                        .param("from", "2026-03-01T00:00:00")
                        .param("to", "2026-03-01T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void 미인증은_401이다() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_감사_로그를_볼_수_없다() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isForbidden());
    }
}
