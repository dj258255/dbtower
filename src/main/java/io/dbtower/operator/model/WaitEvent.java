package io.dbtower.operator.model;

/**
 * Wait Event 통합 뷰 (B1) — load%가 "누가 시간을 쓰나"라면, 이것은 "그 시간에 무엇을 기다렸나"다.
 *
 * 기종마다 값의 의미가 다르다는 점에 주의. 하나의 스키마로 강제로 뭉개지 않고,
 * category와 주석으로 의미 차이를 드러내는 쪽을 택했다.
 *
 * - MySQL / MSSQL / Oracle: 서버 기동 이후 누적 카운터 (count=대기 횟수, totalMs=누적 대기시간)
 * - PostgreSQL: 조회 순간의 활성 세션 스냅샷 (count=지금 그 이벤트를 기다리는 세션 수, totalMs=0 —
 *   stock 이미지의 pg_stat_activity에는 시간 누적이 없다)
 * - MongoDB: 정확한 wait event가 아니라 대기 큐/티켓 지표 (count=큐 길이 또는 사용/가용 티켓 수)
 *
 * @param event    대기 이벤트 이름 (기종 고유 명칭 그대로 — 번역하면 검색이 안 된다)
 * @param category 대기 분류 — IO/Lock/CPU 등. 기종별 분류 체계를 최대한 보존한다
 * @param count    대기 횟수(누적) 또는 현재 대기 중인 세션/티켓 수(스냅샷)
 * @param totalMs  누적 대기시간(ms). 시간 정보가 없는 소스(PG 스냅샷, Mongo 큐 길이)는 0
 */
public record WaitEvent(String event, String category, long count, double totalMs) {
}
