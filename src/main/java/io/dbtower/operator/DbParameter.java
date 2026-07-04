package io.dbtower.operator;

/**
 * 인스턴스 설정 파라미터 하나 (B6 파라미터 드리프트) — 같은 역할의 두 장비 설정값을 비교해
 * "왜 저 장비만 느리지"(한쪽만 work_mem이 작다, max_connections가 다르다)를 추적하는 원천 데이터.
 *
 * value는 기종이 돌려주는 표기 그대로다(정규화·단위 환산하지 않음) — 같은 논리 설정도 기종마다
 * 표기가 달라, 비교는 같은 기종끼리 할 때만 의미가 있다(다르면 ParameterDiffService가 경고를 싣는다).
 *
 * 민감 파라미터(비밀번호·키·자격증명류)는 값을 노출하지 않고 마스킹한다 — 이름 기반 휴리스틱은
 * ParameterSupport 참고. 마스킹된 값은 좌우 모두 같은 토큰이라 diff에서 '변경'으로 튀지 않는다.
 *
 * @param name  파라미터 이름 (기종 고유 명칭 그대로)
 * @param value 파라미터 값 (기종 표기 그대로, 민감값은 마스킹됨)
 * @param unit  값의 단위 — PostgreSQL pg_settings만 제공(예: 8kB, ms). 없는 기종은 null
 */
public record DbParameter(String name, String value, String unit) {
}
