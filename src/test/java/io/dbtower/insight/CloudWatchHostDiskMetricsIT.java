package io.dbtower.insight;

import io.dbtower.insight.internal.CloudWatchHostDiskMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CloudWatch 소스 라이브 통합 (LocalStack) — AWS SDK 실제 경로로 put→get 왕복을 검증한다.
 * 일반 CI에선 LocalStack이 없으므로 환경변수 게이트로 스킵(단위 테스트와 분리). 실행:
 *   DBTOWER_CLOUDWATCH_IT=http://localhost:14566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test ./gradlew test
 * 하강 추세 메트릭을 SDK로 시딩하고, 같은 SDK 클라이언트를 쓰는 소스가 선형 ETA를 산출하는지 본다.
 */
class CloudWatchHostDiskMetricsIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "DBTOWER_CLOUDWATCH_IT", matches = "https?://.+")
    void 하강_추세_메트릭에서_ETA를_산출하고_여유퍼센트는_null이다() {
        String endpoint = System.getenv("DBTOWER_CLOUDWATCH_IT");
        String instanceId = "prod-orders-rds";

        // 시딩 — 6시간 5분 간격 73포인트, 100GB → 11GB 선형 감소(같은 SDK 경로로 put)
        try (CloudWatchClient seed = CloudWatchClient.builder()
                .region(Region.US_EAST_1).endpointOverride(URI.create(endpoint)).build()) {
            List<MetricDatum> data = new ArrayList<>();
            Instant now = Instant.now();
            for (int i = 0; i <= 72; i++) {
                double bytes = 100_000_000_000.0 - i * 1_230_000_000.0;
                data.add(MetricDatum.builder()
                        .metricName("FreeStorageSpace")
                        .dimensions(Dimension.builder().name("DBInstanceIdentifier").value(instanceId).build())
                        .timestamp(now.minus(Duration.ofMinutes(5L * (72 - i))))
                        .value(bytes).unit(StandardUnit.BYTES).build());
            }
            // PutMetricData는 요청당 최대 1000개지만 안전하게 20개씩 나눠 보낸다
            for (int i = 0; i < data.size(); i += 20) {
                seed.putMetricData(PutMetricDataRequest.builder().namespace("AWS/RDS")
                        .metricData(data.subList(i, Math.min(i + 20, data.size()))).build());
            }
        }

        CloudWatchHostDiskMetrics metrics = new CloudWatchHostDiskMetrics("us-east-1", endpoint);

        // 여유 %는 CloudWatch가 총 용량 메트릭이 없어 항상 null(지어내지 않는다)
        assertNull(metrics.diskAvailPct(instanceId));

        // ETA(초) — 감소 추세라 양수여야 하고, 대략 (11GB / (1.23GB/5분)) ≈ 9시간 규모
        Double eta = metrics.diskEtaSeconds(instanceId);
        assertNotNull(eta, "하강 추세 메트릭에서 ETA가 나와야 한다");
        assertTrue(eta > 0, "포화 ETA는 양수(감소 추세)");
        assertTrue(eta < Duration.ofDays(1).getSeconds(), "이 감소 속도면 하루 안 — 실측 ETA=" + eta + "s");

        // 대상 미지정·미존재는 null(추세를 지어내지 않는다)
        assertNull(metrics.diskEtaSeconds(null));
        assertNull(metrics.diskEtaSeconds("no-such-instance"));
    }
}
