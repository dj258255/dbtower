package io.dbtower.alert;

import io.dbtower.alert.internal.InquiryService;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.alert.internal.ReferencedSchemaService;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/**
 * 실발사 확인용 — DBTOWER_WEBHOOK_URL이 설정된 경우에만, 웹 콘솔의 "DB팀에 문의" 버튼이 타는
 * 실제 경로(InquiryService.submit → WebhookNotifier.sendEmbed → Discord HTTP)를 그대로 태운다.
 * CI에는 URL이 없으므로 자동 skip — 로컬에서 눈으로 확인하는 용도.
 */
class WebhookEmbedLiveFireTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void DB팀에_문의_버튼_경로로_실제_embed_발사() {
        String url = System.getenv("DBTOWER_WEBHOOK_URL");
        assumeTrue(url != null && !url.isBlank(), "DBTOWER_WEBHOOK_URL 미설정 — skip");

        RegistryService registry = Mockito.mock(RegistryService.class);
        DatabaseInstance self = new DatabaseInstance(
                "dbtower-self", io.dbtower.registry.DbmsType.POSTGRESQL, "127.0.0.1", 15432, "dbtower", "dbtower", "pw");
        when(registry.findById(1L)).thenReturn(self);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("beomsu", "n/a", List.of()));

        ReferencedSchemaService refSchema = Mockito.mock(ReferencedSchemaService.class);
        InquiryService service = new InquiryService(registry, new WebhookNotifier(url, 12, null), refSchema, new QueryMasker(true, false), "");

        var result = service.submit(1L, new InquiryService.InquiryRequest(
                "SELECT instance_id, captured_at, total_time_ms\nFROM query_snapshot\nWHERE query_id = 'a3f9c2'\nORDER BY captured_at DESC",
                "Seq Scan on query_snapshot (rows=500000)\n  Filter: query_id = 'a3f9c2'",
                List.of("풀 테이블 스캔 의심 (50만 행)", "(instance_id, captured_at) 복합 인덱스 미사용"),
                "query_id 단독 필터가 인덱스 선두 컬럼과 불일치 — 복합 인덱스 추가를 검토하세요. (도그푸딩 실측: 인덱스 후 21.269ms → 0.062ms)",
                "embed 실발사 테스트"));

        assertTrue(result.sent());
    }
}
