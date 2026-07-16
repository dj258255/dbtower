package io.dbtower.insight.internal;

import io.dbtower.insight.HostDiskMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * CloudWatch(RDS) 디스크 지표 — 관리형 DB용 소스. RDS는 node_exporter를 붙일 호스트가 없어
 * AWS가 발행하는 AWS/RDS FreeStorageSpace(여유 바이트)가 유일한 디스크 신호다.
 * nodeFilter = DBInstanceIdentifier(RDS 인스턴스 식별자).
 *
 * 값의 정직 규약:
 * - 여유 %는 null — 총 용량(AllocatedStorage)은 CloudWatch 메트릭이 아니라 RDS API 속성이라
 *   메트릭만으로는 %를 만들 수 없다(지어내지 않는다). 판정은 ETA 축으로만 동작한다.
 * - ETA는 최근 6시간 5분 간격 평균의 최소제곱 선형 회귀 — Prometheus deriv와 같은 선형 가정.
 *   감소 추세가 아니면 null, 표본 2개 미만도 null.
 * - nodeFilter 미지정은 null — CloudWatch는 인스턴스 식별자 없이 집계할 대상이 없다.
 *
 * endpoint 오버라이드는 LocalStack 등 호환 구현 검증용(빈 값이면 AWS 기본). 자격증명은
 * AWS SDK 표준 체인(환경변수 AWS_ACCESS_KEY_ID 등) — 우리 설정 파일에 키를 두지 않는다.
 */
public class CloudWatchHostDiskMetrics implements HostDiskMetrics {

    private static final Logger log = LoggerFactory.getLogger(CloudWatchHostDiskMetrics.class);

    private final CloudWatchClient client;

    public CloudWatchHostDiskMetrics(String region, String endpoint) {
        var builder = CloudWatchClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.client = builder.build();
    }

    @Override
    public boolean configured() {
        return true; // 이 구현이 빈으로 선택된 것 자체가 설정 완료(소스 선택은 MetricsSourceConfig)
    }

    @Override
    public Double diskAvailPct(String nodeFilter) {
        return null; // 총 용량이 메트릭에 없어 %를 만들 수 없다 — 지어내지 않는다(클래스 주석)
    }

    @Override
    public Double diskEtaSeconds(String nodeFilter) {
        if (nodeFilter == null || nodeFilter.isBlank()) {
            return null; // DBInstanceIdentifier 없이는 대상이 없다
        }
        try {
            List<Datapoint> points = client.getMetricStatistics(GetMetricStatisticsRequest.builder()
                            .namespace("AWS/RDS")
                            .metricName("FreeStorageSpace")
                            .dimensions(Dimension.builder().name("DBInstanceIdentifier").value(nodeFilter).build())
                            .startTime(Instant.now().minus(Duration.ofHours(6)))
                            .endTime(Instant.now())
                            .period(300)
                            .statistics(Statistic.AVERAGE)
                            .build())
                    .datapoints().stream()
                    .sorted(Comparator.comparing(Datapoint::timestamp))
                    .toList();
            if (points.size() < 2) {
                return null; // 표본 부족 — 추세를 지어내지 않는다
            }
            // 최소제곱 선형 회귀(바이트/초) — Prometheus deriv와 같은 선형 가정
            double n = points.size(), sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
            long t0 = points.get(0).timestamp().getEpochSecond();
            for (Datapoint p : points) {
                double x = p.timestamp().getEpochSecond() - t0;
                double y = p.average();
                sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
            }
            double denom = n * sumXX - sumX * sumX;
            if (denom == 0) {
                return null;
            }
            double slope = (n * sumXY - sumX * sumY) / denom;
            if (slope >= 0) {
                return null; // 감소 추세 아님 — 포화 ETA 없음
            }
            double latest = points.get(points.size() - 1).average();
            return latest / -slope;
        } catch (Exception e) {
            log.debug("CloudWatch 조회 실패 — 미수집으로 취급: {}", e.getMessage());
            return null; // 기능 게이트와 같은 결 — 지표 실패가 Advisor를 죽이지 않는다
        }
    }
}
