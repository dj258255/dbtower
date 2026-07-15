package io.dbtower.insight.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus 기능 게이트 — 미설정(빈 URL)·연결 불가는 예외가 아니라 빈 결과다.
 * 그래프 한 장 때문에 콘솔이 죽으면 안 된다는 원칙의 최소 보증.
 */
class PrometheusClientTest {

    @Test
    void 미설정이면_configured_false_그리고_빈_결과다() {
        PrometheusClient client = new PrometheusClient("");
        assertThat(client.configured()).isFalse();
        assertThat(client.queryRange("up", Instant.now().minusSeconds(600), Instant.now(), 15)).isEmpty();
    }

    @Test
    void 연결_불가면_예외_없이_빈_결과다() {
        // 아무도 듣지 않는 포트 — 연결 실패가 빈 리스트로 수렴해야 한다
        PrometheusClient client = new PrometheusClient("http://localhost:1");
        assertThat(client.configured()).isTrue();
        assertThat(client.queryRange("up", Instant.now().minusSeconds(600), Instant.now(), 15)).isEmpty();
    }

    @Test
    void 끝_슬래시는_정규화된다() {
        PrometheusClient client = new PrometheusClient("http://localhost:19090///");
        assertThat(client.configured()).isTrue();
    }
}
