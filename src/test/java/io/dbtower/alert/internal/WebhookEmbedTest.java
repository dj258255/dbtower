package io.dbtower.alert.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * embed 경로 — Discord면 embeds 페이로드(한도 절단 포함), 아니면 텍스트 폴백.
 * 레이트리밋 윈도우는 텍스트 경로와 공유한다(embed라고 도배가 허용되지 않는다).
 */
class WebhookEmbedTest {

    static class Capturing extends WebhookNotifier {
        final List<String> texts = new ArrayList<>();
        final List<Embed> embeds = new ArrayList<>();

        Capturing(int ratePerMinute) {
            super("", ratePerMinute, null, null);
        }

        @Override
        void deliver(String message) {
            texts.add(message);
        }

        @Override
        void deliverEmbed(String textFallback, String suppressedNote, Embed embed, Long instanceId) {
            embeds.add(embed);
        }
    }

    @Test
    void 레이트리밋_윈도우는_텍스트와_embed가_공유한다() {
        Capturing n = new Capturing(2);
        long t = 1_000_000L;
        n.sendAt("텍스트1", t);
        n.sendEmbedAt("폴백1", embed("문의"), null, t); // 여기까지 상한
        n.sendEmbedAt("폴백2", embed("문의2"), null, t); // 억제
        assertThat(n.texts).hasSize(1);
        assertThat(n.embeds).hasSize(1);
    }

    @Test
    void discord_페이로드는_embeds와_멘션잠금을_담고_빈_필드는_뺀다() {
        WebhookNotifier n = new WebhookNotifier("https://discord.com/api/webhooks/x", 12, null, null);
        var e = new WebhookNotifier.Embed("DB팀 문의", 0x6366F1, List.of(
                new WebhookNotifier.Embed.Field("요청자", "alice", true),
                new WebhookNotifier.Embed.Field("비고", "", false)));   // 빈 값 — 제외돼야 함
        String payload = n.discordEmbedPayload(e, "");
        assertThat(payload).contains("\"embeds\"")
                .contains("\"title\": \"DB팀 문의\"")
                .contains("\"color\": 6514417")
                .contains("요청자")
                .doesNotContain("비고")
                .contains("\"allowed_mentions\": {\"parse\": []}");
    }

    @Test
    void 필드_값은_디스코드_한도에서_잘리고_잘림_표시가_붙는다() {
        WebhookNotifier n = new WebhookNotifier("https://discord.com/api/webhooks/x", 12, null, null);
        var e = new WebhookNotifier.Embed("t", 0, List.of(
                new WebhookNotifier.Embed.Field("쿼리", "x".repeat(3000), false)));
        String payload = n.discordEmbedPayload(e, "");
        assertThat(payload).contains("… (잘림)");
        assertThat(payload.length()).isLessThan(2500);
    }

    private static WebhookNotifier.Embed embed(String title) {
        return new WebhookNotifier.Embed(title, 0, List.of());
    }
}
