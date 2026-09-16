package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.alert.InquiryRaisedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InquiryTriggerListenerTest {

    private final AiOperationService service = mock(AiOperationService.class);
    private final InquiryRaisedEvent event = new InquiryRaisedEvent("inq-1", 2L, "mysql-a", "alice",
            "SELECT * FROM orders WHERE user_id = ?", List.of("풀 테이블 스캔 의심"), "주문 조회가 느립니다",
            LocalDateTime.of(2026, 9, 16, 3, 30, 41));

    @Test
    void 꺼져_있으면_아무것도_접수하지_않는다() {
        new InquiryTriggerListener(service, false).onInquiry(event);
        verify(service, never()).submit(any());
    }

    @Test
    void 켜져_있으면_문의_유형과_문의_SQL로_접수한다() {
        new InquiryTriggerListener(service, true).onInquiry(event);

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service).submit(captor.capture());
        AiOperationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(AiOperationType.DB_TEAM_INQUIRY);
        assertThat(request.trigger()).isEqualTo(AiOperationTrigger.WEB);
        assertThat(request.requestId()).isEqualTo("inquiry:inq-1");
        assertThat(request.instanceId()).isEqualTo(2L);
        assertThat(request.windowMinutes()).isEqualTo(60);
        assertThat(request.requester()).isEqualTo("alice");
        assertThat(request.team()).isNull();
        assertThat(request.replyChannel()).isNull();
        assertThat(request.prompt()).contains("SELECT * FROM orders WHERE user_id = ?");
        assertThat(request.prompt()).contains("풀 테이블 스캔 의심");
        assertThat(request.prompt()).contains("주문 조회가 느립니다");
    }

    @Test
    void 접수가_실패해도_문의_스레드로_예외를_올리지_않는다() {
        when(service.submit(any())).thenThrow(new IllegalStateException("진행 중인 AI 작업이 3건입니다"));
        assertThatCode(() -> new InquiryTriggerListener(service, true).onInquiry(event))
                .doesNotThrowAnyException();
    }

    @Test
    void 대상_인스턴스가_없으면_접수하지_않는다() {
        InquiryRaisedEvent withoutInstance = new InquiryRaisedEvent("inq-2", null, null, "alice",
                "SELECT 1", List.of(), null, LocalDateTime.now());
        new InquiryTriggerListener(service, true).onInquiry(withoutInstance);
        verify(service, never()).submit(any());
    }

    @Test
    void 같은_문의_이벤트를_두_번_받아도_요청_id가_같다() {
        InquiryTriggerListener listener = new InquiryTriggerListener(service, true);
        listener.onInquiry(event);
        listener.onInquiry(event);

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service, times(2)).submit(captor.capture());
        assertThat(captor.getAllValues().get(0).requestId())
                .isEqualTo(captor.getAllValues().get(1).requestId());
    }

    @Test
    void 아주_긴_SQL은_상한_안에서_잘렸다고_밝힌다() {
        String longSql = "SELECT * FROM orders WHERE " + "user_id = 42 AND ".repeat(400);
        InquiryRaisedEvent longEvent = new InquiryRaisedEvent("inq-3", 2L, "mysql-a", "alice",
                longSql, List.of(), null, LocalDateTime.now());

        new InquiryTriggerListener(service, true).onInquiry(longEvent);

        ArgumentCaptor<AiOperationRequest> captor = ArgumentCaptor.forClass(AiOperationRequest.class);
        verify(service).submit(captor.capture());
        String prompt = captor.getValue().prompt();
        assertThat(prompt.length()).isLessThanOrEqualTo(AiOperationRequest.PROMPT_MAX);
        assertThat(prompt).endsWith("...(잘림)");
    }
}
