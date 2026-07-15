package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;
import io.dbtower.insight.PrometheusClient;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 디스크 포화 예측 (Phase 5) — "지금 여유가 얼마"가 아니라 "이 속도면 며칠 뒤에 차는가"를 계산한다.
 * WAL·binlog·데이터 파일이 차오르는 건 DB 장애 중 가장 예고가 긴 장애인데, 예고를 읽는 쪽이 없었다.
 *
 * 소스는 node_exporter(호스트 지표) — 디스크는 DB가 아니라 노드의 자원이라, 인스턴스의 nodeFilter
 * (Prometheus 라벨 셀렉터, V16)로 호스트를 특정한다. 미지정이면 전 노드 집계(단일 노드 데모에선 정확).
 * ETA는 선형 추세: avail / (-deriv(avail[6h])) — 최근 6시간 감소 속도가 유지된다는 가정을 detail에
 * 명시한다(예측이지 사실이 아니다). 증가 추세(음수 ETA)면 "포화 추세 없음".
 *
 * 게이트: Prometheus 미설정·미수집이면 조용히 지적 없음(기능 게이트 — 값을 지어내지 않는다).
 * 조치는 안내만 — 스토리지 확장(RDS 오토스케일·PVC 확장)은 프로비저닝 계층 소유, DBTower는 신호까지.
 */
@Component
public class DiskForecastAdvisor implements Advisor {

    static final double ETA_CRITICAL_DAYS = 3;
    static final double ETA_WARNING_DAYS = 14;
    static final double AVAIL_WARNING_PCT = 10;

    /** 데이터 디스크로 볼 파일시스템 — tmpfs/overlay 등 가상 마운트는 제외 */
    private static final String FS_EXCLUDE = "fstype!~\"tmpfs|overlay|squashfs|iso9660\"";

    /** 기본 마운트 — nodeFilter가 mountpoint를 직접 지정하면 양보한다(데이터 전용 마운트가 실무 정석). */
    private static final String DEFAULT_MOUNT = "mountpoint=\"/\"";

    private final PrometheusClient prometheus;

    public DiskForecastAdvisor(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    @Override
    public String id() {
        return "disk-forecast";
    }

    @Override
    public String title() {
        return "디스크 포화 예측 (호스트, 선형 추세)";
    }

    @Override
    public boolean supports(DbmsType type) {
        return true;   // 디스크는 호스트 자원 — 기종 무관
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        if (!prometheus.configured()) {
            return List.of();   // 기능 게이트 — Prometheus 없으면 조용히 스킵(지어내지 않는다)
        }
        String selector = selector(instance.getNodeFilter());
        Double availPct = prometheus.queryScalar(
                "min(node_filesystem_avail_bytes{%s} / node_filesystem_size_bytes{%s}) * 100"
                        .formatted(selector, selector));
        // 선형 ETA(초) = 여유 / 감소속도. deriv가 음수(감소)일 때만 양수 ETA가 나온다.
        Double etaSeconds = prometheus.queryScalar(
                "min(node_filesystem_avail_bytes{%s} / (-deriv(node_filesystem_avail_bytes{%s}[6h]) > 0))"
                        .formatted(selector, selector));
        return evaluate(availPct, etaSeconds, instance.getNodeFilter());
    }

    /** 순수 판정 — 실측값만으로 결론(단위 테스트 대상). availPct/etaSeconds null = 미수집·추세 없음. */
    public List<AdvisorFinding> evaluate(Double availPct, Double etaSeconds, String nodeFilter) {
        List<AdvisorFinding> findings = new ArrayList<>();
        if (availPct == null) {
            return findings;   // node_exporter 미수집 — 침묵(기능 게이트)
        }
        String node = nodeFilter == null || nodeFilter.isBlank() ? "전 노드 집계(nodeFilter 미지정)" : nodeFilter;
        if (etaSeconds != null && etaSeconds > 0) {
            double etaDays = etaSeconds / 86_400.0;
            if (etaDays <= ETA_CRITICAL_DAYS) {
                findings.add(new AdvisorFinding(Severity.CRITICAL,
                        "디스크 포화 임박 — 약 %.1f일 내 (여유 %.1f%%)".formatted(etaDays, availPct),
                        "최근 6시간 감소 속도가 유지되면 약 %.1f일 뒤 여유 0이 된다(선형 추세 가정 — 예측이지 사실이 아님). 호스트: %s"
                                .formatted(etaDays, node),
                        "WAL/binlog 보존·대형 적재를 점검하고 스토리지 확장을 준비한다 — 확장 실행은 프로비저닝 계층(RDS 오토스케일·PVC) 소유, DBTower는 신호까지."));
            } else if (etaDays <= ETA_WARNING_DAYS) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "디스크 포화 추세 — 약 %.0f일 내 (여유 %.1f%%)".formatted(etaDays, availPct),
                        "최근 6시간 추세가 유지되면 약 %.0f일 뒤 포화(선형 가정). 호스트: %s".formatted(etaDays, node),
                        "증가 원인(보존 정책·적재 주기)을 확인하고 용량 계획에 반영한다."));
            }
        }
        // 추세와 무관한 절대 여유 경고 — 추세가 없어도 이미 좁으면 위험하다
        if (availPct < AVAIL_WARNING_PCT
                && findings.stream().noneMatch(f -> f.severity() == Severity.CRITICAL)) {
            findings.add(new AdvisorFinding(Severity.WARNING,
                    "디스크 여유 %.1f%% — 절대 여유 부족".formatted(availPct),
                    "포화 추세와 무관하게 여유가 %.0f%% 미만이다. 호스트: %s".formatted(AVAIL_WARNING_PCT, node),
                    "임시 파일·오래된 백업·로그 보존을 정리하거나 스토리지 확장을 검토한다."));
        }
        return findings;
    }

    /**
     * nodeFilter(라벨 셀렉터)를 기본 파일시스템 셀렉터에 결합 — 비면 전 노드의 루트 마운트.
     * nodeFilter가 mountpoint를 직접 지정하면 기본 "/"를 붙이지 않는다 — DB 데이터를 전용
     * 마운트(/data 등)에 두는 게 실무 정석이라, "/"만 보면 정작 데이터 디스크를 놓친다.
     * (PromQL은 같은 라벨의 중복 매처를 AND로 겹치므로 기본값을 빼는 것 외엔 덮을 방법이 없다)
     */
    public static String selector(String nodeFilter) {
        if (nodeFilter == null || nodeFilter.isBlank()) {
            return FS_EXCLUDE + "," + DEFAULT_MOUNT;
        }
        return nodeFilter.contains("mountpoint")
                ? FS_EXCLUDE + "," + nodeFilter
                : FS_EXCLUDE + "," + DEFAULT_MOUNT + "," + nodeFilter;
    }
}
