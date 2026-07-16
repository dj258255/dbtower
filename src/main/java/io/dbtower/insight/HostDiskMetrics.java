package io.dbtower.insight;

/**
 * 호스트 디스크 지표 소스 추상화 (Phase 5 후속) — 디스크 포화 예측이 소비하는 두 값의 의미 계약.
 *
 * Prometheus(node_exporter)와 CloudWatch(RDS FreeStorageSpace)는 쿼리 언어가 달라 문자열 수준
 * 추상화가 불가능하다 — 그래서 "무엇을 묻는가"(의미)로 경계를 긋는다. 판정(임계·문안)은
 * DiskForecastAdvisor 한 곳에 남고, 소스는 값만 공급한다.
 *
 * 값의 정직 규약: 모르는 값은 null(지어내지 않는다). 소스마다 줄 수 있는 값이 다르다 —
 * 예: CloudWatch는 여유 바이트(FreeStorageSpace)만 메트릭이고 총 용량은 API 속성이라
 * 여유 %를 줄 수 없다(null). 판정부는 null 조합을 그대로 수용한다.
 */
public interface HostDiskMetrics {

    /** 소스가 설정돼 있는가 — false면 Advisor가 조용히 스킵한다(기능 게이트). */
    boolean configured();

    /**
     * 디스크 여유 % (0~100) — nodeFilter는 소스별 노드 식별자(Prometheus: 라벨 셀렉터,
     * CloudWatch: DBInstanceIdentifier). 미수집·소스 미지원은 null.
     */
    Double diskAvailPct(String nodeFilter);

    /** 선형 추세 포화 ETA(초) — 감소 추세가 없으면(증가·평탄) null. */
    Double diskEtaSeconds(String nodeFilter);
}
