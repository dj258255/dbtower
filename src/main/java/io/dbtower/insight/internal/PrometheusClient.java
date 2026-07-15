package io.dbtower.insight.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus HTTP API 클라이언트 (query_range) — 웹 콘솔 Monitoring 탭의 CPU·Connections 그래프와
 * Phase 5 디스크 포화 예측이 공유할 시계열 소스.
 *
 * 설계 원칙: Prometheus는 선택 인프라다. 미설정(dbtower.prometheus.url 빈 값)이거나 응답이 없으면
 * 예외 대신 빈 결과를 돌려주고, 화면은 "미수집"을 정직하게 표기한다 — 모니터링 부가 기능이 플랫폼
 * 본체를 죽이면 안 된다(대상 장애 격리와 같은 결).
 */
@Component
public class PrometheusClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public PrometheusClient(@Value("${dbtower.prometheus.url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public record Point(long epochSec, double value) {
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    /**
     * 구간 시계열 조회. 단일 시계열을 낳는 PromQL을 전제로 첫 series만 취한다(호출부가 avg/sum으로 접어 보낸다).
     * 실패(미설정·연결 불가·비정상 응답)는 빈 리스트 — 호출부가 note로 정직 표기.
     */
    public List<Point> queryRange(String promql, Instant from, Instant to, int stepSeconds) {
        if (!configured()) {
            return List.of();
        }
        try {
            String url = baseUrl + "/api/v1/query_range?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)
                    + "&start=" + from.getEpochSecond() + "&end=" + to.getEpochSecond() + "&step=" + stepSeconds;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return List.of();
            }
            JsonNode result = mapper.readTree(res.body()).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return List.of();
            }
            List<Point> points = new ArrayList<>();
            for (JsonNode v : result.get(0).path("values")) {
                points.add(new Point(v.get(0).asLong(), v.get(1).asDouble()));
            }
            return points;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // 연결 불가·파싱 실패 전부 "미수집"으로 수렴 — 그래프 한 장 때문에 콘솔이 죽지 않게 한다
            return List.of();
        }
    }
}
