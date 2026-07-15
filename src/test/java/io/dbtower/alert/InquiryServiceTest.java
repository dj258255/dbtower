package io.dbtower.alert;

import io.dbtower.alert.internal.InquiryService;
import io.dbtower.alert.internal.ReferencedSchemaService;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 문의 서비스 검증 — 메시지 포맷(인스턴스명·기종·요청자·쿼리·규칙 지적 포함),
 * 웹훅 설정 시 notifier.send 호출, 미설정 시 sent:false + 원인.
 * 실제 외부 전송은 하지 않는다(notifier는 mock).
 */
class InquiryServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
    private final ReferencedSchemaService referencedSchema = Mockito.mock(ReferencedSchemaService.class);
    private final InquiryService service = new InquiryService(registryService, notifier, referencedSchema);

    private void stubInstance() {
        DatabaseInstance instance = new DatabaseInstance(
                "prod-orders", DbmsType.MYSQL, "127.0.0.1", 3306, "orders", "root", "pw");
        when(registryService.findById(1L)).thenReturn(instance);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 웹훅_설정되면_send를_호출하고_sent_true() {
        stubInstance();
        when(notifier.isConfigured()).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));

        var req = new InquiryService.InquiryRequest(
                "SELECT * FROM orders WHERE user_id = 42",
                "-> Seq Scan on orders",
                List.of("풀 테이블 스캔 의심", "인덱스 미사용"),
                "user_id 인덱스를 검토하세요",
                "주문 조회가 느립니다");

        InquiryService.InquiryResult result = service.submit(1L, req);

        assertTrue(result.sent());
        assertNull(result.reason());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<WebhookNotifier.Embed> embedCaptor = ArgumentCaptor.forClass(WebhookNotifier.Embed.class);
        verify(notifier).sendEmbed(captor.capture(), embedCaptor.capture());
        String msg = captor.getValue();
        assertTrue(msg.contains("prod-orders"), "인스턴스명 포함");
        assertTrue(msg.contains("MYSQL"), "기종 포함");
        assertTrue(msg.contains("alice"), "요청자 포함");
        assertTrue(msg.contains("SELECT * FROM orders WHERE user_id = 42"), "쿼리 포함");
        assertTrue(msg.contains("풀 테이블 스캔 의심"), "규칙 지적 포함");
        assertTrue(msg.contains("user_id 인덱스를 검토하세요"), "AI 분석 포함");
        assertTrue(msg.contains("주문 조회가 느립니다"), "비고 포함");

        // embed도 같은 내용을 구조화해 담는다 — 쿼리는 sql 코드블록, 요청자·인스턴스는 인라인 필드
        WebhookNotifier.Embed embed = embedCaptor.getValue();
        assertTrue(embed.title().contains("DB팀 문의"));
        String joined = embed.fields().stream()
                .map(f -> f.name() + "=" + f.value())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("요청자=alice"));
        assertTrue(joined.contains("prod-orders (MYSQL)"));
        assertTrue(joined.contains("```sql"), "쿼리는 sql 코드블록");
        assertTrue(joined.contains("SELECT * FROM orders"));
        assertTrue(joined.contains("- 풀 테이블 스캔 의심"));
    }

    @Test
    void 웹훅_미설정이면_send를_호출하지_않고_sent_false() {
        stubInstance();
        when(notifier.isConfigured()).thenReturn(false);

        InquiryService.InquiryResult result = service.submit(1L,
                new InquiryService.InquiryRequest("SELECT 1", null, null, null, null));

        assertFalse(result.sent());
        assertTrue(result.reason().contains("DBTOWER_WEBHOOK_URL"));
        verify(notifier, never()).send(anyString());
        verify(notifier, never()).sendEmbed(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 분석_없이_쿼리만으로도_문의할_수_있다() {
        stubInstance();
        when(notifier.isConfigured()).thenReturn(true);

        InquiryService.InquiryResult result = service.submit(1L,
                new InquiryService.InquiryRequest("SELECT 1", null, null, null, null));

        assertTrue(result.sent());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<WebhookNotifier.Embed> embedCaptor = ArgumentCaptor.forClass(WebhookNotifier.Embed.class);
        verify(notifier).sendEmbed(captor.capture(), embedCaptor.capture());
        String msg = captor.getValue();
        assertTrue(msg.contains("SELECT 1"));
        // 선택 항목이 없으면 해당 섹션 헤더 자체가 없다
        assertFalse(msg.contains("실행계획:"));
        assertFalse(msg.contains("규칙 지적:"));
        assertFalse(msg.contains("AI 분석:"));
    }
}
