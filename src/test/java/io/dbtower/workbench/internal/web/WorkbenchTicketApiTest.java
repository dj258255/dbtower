package io.dbtower.workbench.internal.web;

import io.dbtower.workbench.internal.ChangeExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 티켓 실행 API의 요청 본문 바인딩 — 앱이 실제로 쓰는 Jackson 설정 그대로 검증한다.
 *
 * <p>서비스 테스트는 HTTP를 거치지 않아 Jackson 3의 원시 boolean 누락 거부(`{}` -> 400)를 못 봤고, 라이브에서야 드러났다
 * (VERIFICATION 130절). 빠진 값을 어떻게 해석하는지가 곧 안전 계약이라 여기서 고정한다: 캡처 포기와 실제 되돌리기·정리 결과는
 * 명시해야만 전달되고, 명시가 빠지면 추측하지 않고 거부한다.
 */
@SpringBootTest(properties = "dbtower.security.api-token=test-api-token")
@AutoConfigureMockMvc
class WorkbenchTicketApiTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChangeExecutionService changes;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 빈_본문의_드라이런은_캡처를_포기하지_않는다() throws Exception {
        mvc.perform(post("/api/workbench/tickets/7/dry-run").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(changes).dryRun(7L, false);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 본문_없는_실행도_캡처를_유지하고_캡처_포기는_명시해야만_전달된다() throws Exception {
        mvc.perform(post("/api/workbench/tickets/7/execute").with(csrf())).andExpect(status().isOk());
        verify(changes).execute(7L, false);

        mvc.perform(post("/api/workbench/tickets/8/execute").with(csrf()).contentType("application/json")
                .content("{\"withoutCapture\":true}")).andExpect(status().isOk());
        verify(changes).execute(8L, true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 되돌리기는_dryRun이_빠지면_실제_되돌리기로_추측하지_않고_거부한다() throws Exception {
        mvc.perform(post("/api/workbench/tickets/7/revert").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verify(changes, never()).revert(anyLong(), anyBoolean());

        mvc.perform(post("/api/workbench/tickets/7/revert").with(csrf()).contentType("application/json")
                .content("{\"dryRun\":true}")).andExpect(status().isOk());
        verify(changes).revert(7L, true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 커밋_불명_정리는_확인_결과가_빠지면_거부한다() throws Exception {
        mvc.perform(post("/api/workbench/tickets/7/resolve").with(csrf()).contentType("application/json")
                .content("{\"note\":\"root로 조회해 확인\"}")).andExpect(status().isBadRequest());
        verify(changes, never()).resolve(anyLong(), anyBoolean(), anyString());

        mvc.perform(post("/api/workbench/tickets/7/resolve").with(csrf()).contentType("application/json")
                .content("{\"applied\":false,\"note\":\"root로 조회해 확인\"}")).andExpect(status().isOk());
        verify(changes).resolve(7L, false, "root로 조회해 확인");
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_커밋_불명_정리를_할_수_없다() throws Exception {
        mvc.perform(post("/api/workbench/tickets/7/resolve").with(csrf()).contentType("application/json")
                .content("{\"applied\":true,\"note\":\"root로 조회해 확인\"}")).andExpect(status().isForbidden());
        verify(changes, never()).resolve(anyLong(), anyBoolean(), anyString());
    }
}
