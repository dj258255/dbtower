package io.dbtower.alert.internal;

import io.dbtower.alert.AlertMuter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알람 스킵(음소거) — 강제 지점은 WebhookNotifier 한 곳: 중지된 인스턴스의 embed는 발사되지 않고,
 * 다른 인스턴스는 정상 발사되며, 만료되면 자동 재개된다.
 */
class WebhookMuteTest {

    static class Capturing extends WebhookNotifier {
        final List<Long> delivered = new ArrayList<>();

        Capturing(AlertMuter muter) {
            super("https://discord.com/api/webhooks/x", 100, null, muter);
        }

        @Override
        void deliverEmbed(String textFallback, String suppressedNote, Embed embed, Long instanceId) {
            delivered.add(instanceId);
        }
    }

    @Test
    void 중지된_인스턴스만_발사가_생략된다() {
        AlertMuter muter = new AlertMuter();
        muter.mute(5L, Duration.ofHours(1));
        Capturing n = new Capturing(muter);

        n.sendEmbed("a", 5L, new WebhookNotifier.Embed("t", 0, List.of()));
        n.sendEmbed("b", 7L, new WebhookNotifier.Embed("t", 0, List.of()));

        assertThat(n.delivered).containsExactly(7L);
        assertThat(muter.remainingMinutes(5L)).isGreaterThan(50);
    }

    @Test
    void 만료되면_자동_재개된다() {
        AlertMuter muter = new AlertMuter();
        muter.mute(5L, Duration.ofMillis(-1)); // 이미 만료된 중지
        assertThat(muter.isMuted(5L)).isFalse();
    }
}
