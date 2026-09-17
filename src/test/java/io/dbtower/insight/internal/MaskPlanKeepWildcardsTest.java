package io.dbtower.insight.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskPlanKeepWildcardsTest {

    @Test
    void 값은_지우고_앞뒤_퍼센트_자리만_남긴다() {
        assertThat(AiMaskingTradeoffExperimentIT.maskPlanKeepWildcards(
                "\"Filter\": \"((email)::text ~~ '%@gmail.com'::text)\""))
                .isEqualTo("\"Filter\": \"((email)::text ~~ '%?'::text)\"");
        assertThat(AiMaskingTradeoffExperimentIT.maskPlanKeepWildcards("like 'kim%' and s = 'PAID' and x = 'it''s%'"))
                .isEqualTo("like '?%' and s = '?' and x = '?%'");
        assertThat(AiMaskingTradeoffExperimentIT.maskPlanKeepWildcards("a = '%' and b = '%x%'"))
                .isEqualTo("a = '%?' and b = '%?%'");
    }
}
