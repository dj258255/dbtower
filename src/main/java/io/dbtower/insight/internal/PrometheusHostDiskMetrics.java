package io.dbtower.insight.internal;

import io.dbtower.insight.HostDiskMetrics;
import io.dbtower.insight.PrometheusClient;

/**
 * Prometheus(node_exporter) 디스크 지표 — 셀프호스트 기본 소스 (Phase 5의 원 구현을 소스로 분리).
 *
 * 셀렉터 규약(78절): 가상 파일시스템 제외 + 기본 mountpoint="/", nodeFilter가 mountpoint를
 * 직접 지정하면 기본을 양보(PromQL 중복 매처는 AND라 덮어쓸 수 없다 — 데이터 전용 마운트가 실무 정석).
 */
public class PrometheusHostDiskMetrics implements HostDiskMetrics {

    private static final String FS_EXCLUDE = "fstype!~\"tmpfs|overlay|squashfs|iso9660\"";
    private static final String DEFAULT_MOUNT = "mountpoint=\"/\"";

    private final PrometheusClient prometheus;

    public PrometheusHostDiskMetrics(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    @Override
    public boolean configured() {
        return prometheus.configured();
    }

    @Override
    public Double diskAvailPct(String nodeFilter) {
        String selector = selector(nodeFilter);
        return prometheus.queryScalar(
                "min(node_filesystem_avail_bytes{%s} / node_filesystem_size_bytes{%s}) * 100"
                        .formatted(selector, selector));
    }

    @Override
    public Double diskEtaSeconds(String nodeFilter) {
        String selector = selector(nodeFilter);
        // 선형 ETA(초) = 여유 / 감소속도. deriv가 음수(감소)일 때만 양수 ETA가 나온다.
        return prometheus.queryScalar(
                "min(node_filesystem_avail_bytes{%s} / (-deriv(node_filesystem_avail_bytes{%s}[6h]) > 0))"
                        .formatted(selector, selector));
    }

    /** nodeFilter(라벨 셀렉터)를 기본 파일시스템 셀렉터에 결합 — 비면 전 노드의 루트 마운트. */
    public static String selector(String nodeFilter) {
        if (nodeFilter == null || nodeFilter.isBlank()) {
            return FS_EXCLUDE + "," + DEFAULT_MOUNT;
        }
        return nodeFilter.contains("mountpoint")
                ? FS_EXCLUDE + "," + nodeFilter
                : FS_EXCLUDE + "," + DEFAULT_MOUNT + "," + nodeFilter;
    }
}
