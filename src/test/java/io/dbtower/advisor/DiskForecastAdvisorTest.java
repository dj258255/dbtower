package io.dbtower.advisor;

import io.dbtower.advisor.internal.DiskForecastAdvisor;
import io.dbtower.insight.HostDiskMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 디스크 포화 예측 판정 (Phase 5) — 순수 판정 로직(evaluate)만 검증한다.
 * Prometheus 실측값은 PrometheusClient가 가져오고, 여기서는 "값이 이렇게 나왔을 때
 * 결론이 맞는가"를 고정한다. ETA 임계(3일/14일)와 절대 여유(10%) 경계, 그리고
 * 미수집 침묵(기능 게이트)이 핵심.
 */
class DiskForecastAdvisorTest {

    /** 소스는 판정과 무관 — 미설정 소스로 순수 판정만 시험한다 */
    private static final HostDiskMetrics NO_SOURCE = new HostDiskMetrics() {
        public boolean configured() { return false; }
        public Double diskAvailPct(String nodeFilter) { return null; }
        public Double diskEtaSeconds(String nodeFilter) { return null; }
    };

    private final DiskForecastAdvisor advisor = new DiskForecastAdvisor(NO_SOURCE);

    private static final double DAY = 86_400;

    @Test
    void 수집값이_없으면_침묵한다() {
        // node_exporter 미수집 — 값을 지어내지 않고 지적 없음(기능 게이트)
        assertTrue(advisor.evaluate(null, null, null).isEmpty());
    }

    @Test
    void 삼일_내_포화는_CRITICAL() {
        List<AdvisorFinding> findings = advisor.evaluate(40.0, 2.5 * DAY, "instance=\"db-node-3:9100\"");
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).detail().contains("db-node-3"));
        // 선형 추세는 가정이지 사실이 아니다 — 예측임을 명시해야 한다
        assertTrue(findings.get(0).detail().contains("선형"));
    }

    @Test
    void 십사일_내_포화는_WARNING() {
        List<AdvisorFinding> findings = advisor.evaluate(60.0, 10 * DAY, null);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).detail().contains("전 노드 집계"));
    }

    @Test
    void 십사일_초과면_추세_지적_없음() {
        assertTrue(advisor.evaluate(60.0, 30 * DAY, null).isEmpty());
    }

    @Test
    void 추세가_없어도_여유가_10퍼센트_미만이면_WARNING() {
        // 디스크가 줄고 있진 않지만(etaSeconds null) 이미 좁다 — 절대 여유 경고
        List<AdvisorFinding> findings = advisor.evaluate(7.2, null, null);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("7.2"));
    }

    @Test
    void CRITICAL이_있으면_절대여유_경고는_중복하지_않는다() {
        // 여유 5% + 2일 내 포화 — CRITICAL 하나면 충분(같은 디스크에 경고 두 장은 소음)
        List<AdvisorFinding> findings = advisor.evaluate(5.0, 2 * DAY, null);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
    }

    @Test
    void 여유가_넉넉하고_추세도_없으면_통과() {
        assertTrue(advisor.evaluate(72.0, null, null).isEmpty());
    }



    @Test
    void 여유퍼센트_없이_ETA만_있어도_판정한다_CloudWatch_축() {
        // CloudWatch는 총 용량이 메트릭에 없어 여유 %가 null — ETA 축만으로 치명 판정이 돼야 한다
        List<AdvisorFinding> findings = advisor.evaluate(null, 2 * DAY, "my-rds-instance");
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("여유% 미수집"));
    }
}
