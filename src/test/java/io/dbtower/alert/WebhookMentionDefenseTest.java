package io.dbtower.alert;

import io.dbtower.alert.internal.WebhookNotifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-9 웹훅 멘션 방어 + 제어문자 이스케이프 — Discord payload에 allowed_mentions.parse=[]가 붙어
 * @everyone/@here·역할 멘션이 인젝션되지 않고, \r 등 제어문자가 JSON으로 안전하게 이스케이프되는지
 * 로컬 HTTP 서버로 실제 전송 바디를 받아 검증한다(B-5 리팩터 후에도 deliver는 호출 스레드에서 동기 전송).
 */
class WebhookMentionDefenseTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 경로에 "discord.com"을 넣어 WebhookNotifier가 Discord 포맷을 고르게 한다(URL contains 판정).
        server.createContext("/discord.com/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/discord.com/webhook";
    }

    @Test
    void Discord_payload에_allowed_mentions와_제어문자_이스케이프가_적용된다() {
        WebhookNotifier notifier = new WebhookNotifier(url(), 12, null, null);

        // @everyone 멘션 시도 + 캐리지리턴(\r) 제어문자 포함
        notifier.send("[경보] @everyone 위험\r다음줄\t탭");

        String body = lastBody.get();
        assertThat(body).isNotNull();
        // 멘션 방어: parse=[] 로 어떤 멘션도 해석하지 않는다
        assertThat(body).contains("\"allowed_mentions\": {\"parse\": []}");
        // @everyone 문자열 자체는 content에 남지만(정보 보존), allowed_mentions가 실제 멘션 발화를 막는다
        assertThat(body).contains("@everyone");
        // 제어문자는 이스케이프되어 날것으로 남지 않는다
        assertThat(body).contains("\\r").contains("\\t");
        assertThat(body).doesNotContain("\r");
    }
}
