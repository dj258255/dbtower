package io.dbtower.insight.internal;

import io.dbtower.insight.HostDiskMetrics;
import io.dbtower.insight.PrometheusClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 디스크 지표 소스 선택 (Phase 5 후속 — 소스 추상화의 배선 지점).
 *
 * dbtower.disk-forecast.source: prometheus(기본, 셀프호스트) | cloudwatch(RDS).
 * DiskForecastAdvisor는 HostDiskMetrics 하나만 주입받으므로, 새 소스 추가는 구현 1개 + 여기 분기
 * 1줄이다 — "이 한 지점만 바꾸면 된다"던 로드맵 구조 약속의 이행.
 */
@Configuration
public class MetricsSourceConfig {

    @Bean
    public HostDiskMetrics hostDiskMetrics(PrometheusClient prometheusClient,
                                           @Value("${dbtower.disk-forecast.source:prometheus}") String source,
                                           @Value("${dbtower.cloudwatch.region:us-east-1}") String region,
                                           @Value("${dbtower.cloudwatch.endpoint:}") String endpoint) {
        return switch (source) {
            case "cloudwatch" -> new CloudWatchHostDiskMetrics(region, endpoint);
            case "prometheus" -> new PrometheusHostDiskMetrics(prometheusClient);
            default -> throw new IllegalStateException(
                    "dbtower.disk-forecast.source는 prometheus|cloudwatch — 알 수 없는 값: " + source);
        };
    }
}
