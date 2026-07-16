package io.dbtower.alert.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 폭주 제어 (Phase F) — 분당 상한 안에선 즉시 보내되, 초과분은 버리지 않고 개수만 세었다가
 * 다음 허용 알림에 "N건 더" 한 줄로 합친다. 시각을 주입해 슬라이딩 윈도우 판정을 결정적으로 검증한다.
 */
class WebhookRateLimitTest {

    /** deliver를 가로채 실제 전송 대신 기록만 한다(HTTP 없이 레이트리밋 판정만 검증). */
    static class Capturing extends WebhookNotifier {
        final List<String> delivered = new ArrayList<>();

        Capturing(int ratePerMinute) {
            super("", ratePerMinute, null); // URL 없음 — deliver를 오버라이드하므로 무관
        }

        @Override
        void deliver(String message) {
            delivered.add(message);
        }
    }

    @Test
    void 분당_상한_안에서는_모두_보낸다() {
        Capturing n = new Capturing(3);
        long t = 1_000_000L;
        n.sendAt("a", t);
        n.sendAt("b", t);
        n.sendAt("c", t);
        assertThat(n.delivered).containsExactly("a", "b", "c");
    }

    @Test
    void 상한_초과분은_억제되고_다음_허용_알림에_합산된다() {
        Capturing n = new Capturing(3);
        long t = 1_000_000L;
        n.sendAt("a", t);
        n.sendAt("b", t);
        n.sendAt("c", t);   // 여기까지 상한
        n.sendAt("d", t);   // 억제 1
        n.sendAt("e", t);   // 억제 2
        assertThat(n.delivered).hasSize(3); // 초과분은 즉시 전송 안 됨

        // 60초 지나 윈도우가 비워지면 다음 전송이 허용되고 억제분이 합산된다
        n.sendAt("f", t + 61_000);
        assertThat(n.delivered).hasSize(4);
        assertThat(n.delivered.get(3)).contains("f").contains("억제된 알림 2건");
    }

    @Test
    void 합산_후_억제_카운트는_초기화된다() {
        Capturing n = new Capturing(1);
        long t = 1_000_000L;
        n.sendAt("a", t);        // 전송
        n.sendAt("b", t);        // 억제 1
        n.sendAt("c", t + 61_000); // 윈도우 비움 → 전송 + "억제 1건" 합산
        n.sendAt("d", t + 61_000); // 다시 억제 1(합산 리셋됐으므로)
        n.sendAt("e", t + 122_000); // 윈도우 비움 → 전송 + "억제 1건"(2건 아님)
        assertThat(n.delivered).hasSize(3);
        assertThat(n.delivered.get(2)).contains("억제된 알림 1건");
    }
}
