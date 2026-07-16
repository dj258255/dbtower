package io.dbtower.insight;

import io.dbtower.insight.internal.PrometheusHostDiskMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prometheus 디스크 소스의 셀렉터 규약 (78절에서 소스 분리로 이동) — 가상 FS 제외 + 기본 루트,
 * nodeFilter가 mountpoint를 지정하면 기본을 양보(PromQL 중복 매처는 AND라 덮어쓸 수 없다).
 */
class PrometheusHostDiskMetricsTest {

    @Test
    void 셀렉터는_nodeFilter를_기본_파일시스템_조건에_결합한다() {
        assertEquals("fstype!~\"tmpfs|overlay|squashfs|iso9660\",mountpoint=\"/\"",
                PrometheusHostDiskMetrics.selector(null));
        assertEquals("fstype!~\"tmpfs|overlay|squashfs|iso9660\",mountpoint=\"/\",instance=\"db1:9100\"",
                PrometheusHostDiskMetrics.selector("instance=\"db1:9100\""));
    }

    @Test
    void nodeFilter가_mountpoint를_지정하면_기본_루트를_양보한다() {
        // 데이터 전용 마운트(/data 등)가 실무 정석 — 기본 "/"를 겹치면 AND가 돼 빈 결과가 된다
        assertEquals("fstype!~\"tmpfs|overlay|squashfs|iso9660\",mountpoint=\"/data\"",
                PrometheusHostDiskMetrics.selector("mountpoint=\"/data\""));
    }
}
