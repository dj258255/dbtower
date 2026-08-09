package io.dbtower.analysis;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 계수의 노출 계약 — 계수 이름·태그가 /actuator/prometheus에 어떤 문자열로 나오는지 못박는다.
 *
 * 단위 검증(AiAnalyzerTest)은 레지스트리에 값이 올라가는 것까지만 본다. 그런데 대시보드가 의존하는
 * 것은 노출된 이름이다. Micrometer가 점을 밑줄로 바꾸고 Counter에 _total을 붙이므로, 코드에서
 * dbtower.ai.tokens를 바꾸면 쿼리는 조용히 빈 결과를 돌려준다 — 에러도 경고도 없이 그래프만 비는
 * 종류의 실패라 여기서 계약으로 고정한다.
 *
 * AiAnalyzer가 MeterRegistry를 실제로 주입받는지도 이 컨텍스트 기동으로 함께 확인된다.
 *
 * 두 가지를 여기서 켜는 이유:
 * (1) 테스트용 application.yml이 본 설정을 통째로 대체해 actuator 노출이 기본값(health)으로 돌아간다.
 * (2) Spring Boot는 테스트 컨텍스트에서 메트릭 익스포터를 기본으로 끈다 — MeterRegistry가 Simple로
 *     떨어지고 PrometheusScrapeEndpoint가 등록되지 않아 /actuator/prometheus가 404가 된다.
 *     이건 속성으로 못 뒤집는다(끄는 쪽 property source가 @SpringBootTest(properties)보다 우선한다).
 *     Boot 4는 그 해제 스위치를 @AutoConfigureMetrics로 제공한다(3.x의 @AutoConfigureObservability).
 * 공용 테스트 설정을 건드리는 대신 이 테스트가 필요한 것만 선언한다.
 */
@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
@AutoConfigureMetrics
@AutoConfigureMockMvc
class AiTokenMetricIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    AiAnalyzer aiAnalyzer;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 토큰_계수가_prometheus_노출_형식으로_나온다() throws Exception {
        // 계수는 첫 증가 때 등록된다 — AI 백엔드 없이도 노출 형식을 재현하려고 직접 올린다.
        // 실제 값은 AiAnalyzer가 API·CLI 두 경로 모두에서 이 같은 함수로 올린다.
        AiAnalyzer.recordTokens(registry, aiAnalyzer.backend(), AiAnalyzer.CallSite.DIAGNOSE,
                2, 5369, 31040, 196);

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // 이름: 점은 밑줄로, Counter에는 _total이 붙는다
                .andExpect(content().string(containsString("dbtower_ai_tokens_total")))
                // 태그 세 축이 살아 있어야 비율(cache_read/(cache_read+cache_write))과 기능별 분해가 된다
                .andExpect(content().string(containsString("call_site=\"diagnose\"")))
                .andExpect(content().string(containsString("type=\"cache_read\"")))
                .andExpect(content().string(containsString("type=\"cache_write\"")));
    }
}
