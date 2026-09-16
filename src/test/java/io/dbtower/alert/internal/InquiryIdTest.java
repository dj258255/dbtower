package io.dbtower.alert.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문의 멱등 키 — 더블클릭·재시도를 한 건으로 묶되, 다음 날 같은 질문은 새 건이 되어야 한다.
 * 실제 시각에 기대면 분 경계에서 흔들려, 고정 시각으로 계산만 검증한다.
 */
class InquiryIdTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 16, 14, 30, 10);

    @Test
    void 같은_사람이_같은_대상에_같은_내용을_같은_분에_보내면_한_건이다() {
        assertThat(InquiryService.inquiryId(2L, "alice", "SELECT 1", "메모", AT))
                .isEqualTo(InquiryService.inquiryId(2L, "alice", "SELECT 1", "메모", AT.withSecond(59)));
    }

    @Test
    void 분이_다르거나_사람_대상_내용이_다르면_다른_건이다() {
        String base = InquiryService.inquiryId(2L, "alice", "SELECT 1", "메모", AT);
        assertThat(InquiryService.inquiryId(2L, "alice", "SELECT 1", "메모", AT.plusMinutes(1))).isNotEqualTo(base);
        assertThat(InquiryService.inquiryId(3L, "alice", "SELECT 1", "메모", AT)).isNotEqualTo(base);
        assertThat(InquiryService.inquiryId(2L, "bob", "SELECT 1", "메모", AT)).isNotEqualTo(base);
        assertThat(InquiryService.inquiryId(2L, "alice", "SELECT 2", "메모", AT)).isNotEqualTo(base);
        assertThat(InquiryService.inquiryId(2L, "alice", "SELECT 1", "다른 메모", AT)).isNotEqualTo(base);
    }

    @Test
    void null_인자에도_계산된다() {
        assertThat(InquiryService.inquiryId(null, "alice", null, null, AT)).isNotBlank();
    }
}
