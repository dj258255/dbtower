package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;
import io.dbtower.insight.HostDiskMetrics;
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
 * 값의 소스는 HostDiskMetrics 추상화 뒤에 있다 — 셀프호스트는 Prometheus(node_exporter),
 * RDS는 CloudWatch(FreeStorageSpace). 판정(임계·문안)은 여기 한 곳: ETA≤3일 CRITICAL,
 * ≤14일 WARNING, 추세 없어도 여유<10% WARNING. ETA는 선형 추세 가정을 detail에 명시한다
 * (예측이지 사실이 아니다). 소스가 못 주는 값은 null — 그 축의 판정만 건너뛴다
 * (예: CloudWatch는 여유 %가 없어 ETA 축만 동작).
 *
 * 게이트: 소스 미설정·미수집이면 조용히 지적 없음(기능 게이트 — 값을 지어내지 않는다).
 * 조치는 안내만 — 스토리지 확장(RDS 오토스케일·PVC 확장)은 프로비저닝 계층 소유, DBTower는 신호까지.
 */
@Component
public class DiskForecastAdvisor implements Advisor {

    static final double ETA_CRITICAL_DAYS = 3;
    static final double ETA_WARNING_DAYS = 14;
    static final double AVAIL_WARNING_PCT = 10;

    private final HostDiskMetrics metrics;

    public DiskForecastAdvisor(HostDiskMetrics metrics) {
        this.metrics = metrics;
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
    public boolean hostScoped() {
        return true;   // 판정 대상이 DB가 아니라 호스트 디스크 — 같은 호스트 공유 인스턴스엔 스윕당 1회
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        if (!metrics.configured()) {
            return List.of();   // 기능 게이트 — 소스 미설정이면 조용히 스킵(지어내지 않는다)
        }
        return evaluate(metrics.diskAvailPct(instance.getNodeFilter()),
                metrics.diskEtaSeconds(instance.getNodeFilter()),
                instance.getNodeFilter());
    }

    /**
     * 순수 판정 — 실측값만으로 결론(단위 테스트 대상). null = 그 값 미수집.
     * 둘 다 null이면 침묵, 한 축만 있으면 그 축만 판정한다(소스마다 줄 수 있는 값이 다르다).
     */
    public List<AdvisorFinding> evaluate(Double availPct, Double etaSeconds, String nodeFilter) {
        List<AdvisorFinding> findings = new ArrayList<>();
        if (availPct == null && etaSeconds == null) {
            return findings;   // 미수집 — 침묵(기능 게이트)
        }
        String node = nodeFilter == null || nodeFilter.isBlank() ? "전 노드 집계(nodeFilter 미지정)" : nodeFilter;
        String avail = availPct == null ? "여유% 미수집" : "여유 %.1f%%".formatted(availPct);
        if (etaSeconds != null && etaSeconds > 0) {
            double etaDays = etaSeconds / 86_400.0;
            if (etaDays <= ETA_CRITICAL_DAYS) {
                findings.add(new AdvisorFinding(Severity.CRITICAL,
                        "디스크 포화 임박 — 약 %.1f일 내 (%s)".formatted(etaDays, avail),
                        "최근 6시간 감소 속도가 유지되면 약 %.1f일 뒤 여유 0이 된다(선형 추세 가정 — 예측이지 사실이 아님). 호스트: %s"
                                .formatted(etaDays, node),
                        "WAL/binlog 보존·대형 적재를 점검하고 스토리지 확장을 준비한다 — 확장 실행은 프로비저닝 계층(RDS 오토스케일·PVC) 소유, DBTower는 신호까지."));
            } else if (etaDays <= ETA_WARNING_DAYS) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "디스크 포화 추세 — 약 %.0f일 내 (%s)".formatted(etaDays, avail),
                        "최근 6시간 추세가 유지되면 약 %.0f일 뒤 포화(선형 가정). 호스트: %s".formatted(etaDays, node),
                        "증가 원인(보존 정책·적재 주기)을 확인하고 용량 계획에 반영한다."));
            }
        }
        // 추세와 무관한 절대 여유 경고 — 추세가 없어도 이미 좁으면 위험하다(여유 %를 주는 소스만)
        if (availPct != null && availPct < AVAIL_WARNING_PCT
                && findings.stream().noneMatch(f -> f.severity() == Severity.CRITICAL)) {
            findings.add(new AdvisorFinding(Severity.WARNING,
                    "디스크 여유 %.1f%% — 절대 여유 부족".formatted(availPct),
                    "포화 추세와 무관하게 여유가 %.0f%% 미만이다. 호스트: %s".formatted(AVAIL_WARNING_PCT, node),
                    "임시 파일·오래된 백업·로그 보존을 정리하거나 스토리지 확장을 검토한다."));
        }
        return findings;
    }
}
