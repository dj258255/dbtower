package io.dbtower.analysis;

import java.util.List;

/**
 * 심층 원인 진단 결과 (D9) — "무엇이 느린가"를 넘어 "왜 인덱스를 못 타나"를 담는다.
 *
 * 두 축으로 근본원인을 짚는다:
 * 1) worstGap — 옵티마이저 추정 행수 vs 실제 행수의 괴리(카디널리티 오추정). 추정이 크게 틀리면
 *    옵티마이저는 "그 추정 기준으로는 합리적인" 나쁜 플랜을 고른다. 느린 노드가 아니라 추정·실제가
 *    가장 크게 갈라지는 가장 아래(최하위) 노드가 원인이다.
 * 2) rootCauses — 인덱스 무력화 근본원인 5종(암시적 형변환·컬럼함수·통계노후·낮은선택도·복합선두누락) 매칭.
 *
 * notes에는 정직성 안내를 싣는다(loops당 평균의 총량 환산 주의, 파싱 한계, 스키마 미가용 등).
 * docs/ai-analysis-rules.md "심층 원인 규칙 (D9)" 절이 이 판정의 스펙이다.
 *
 * @param plan        실제 실행 계획 원문(기종별 JSON/텍스트/XML) — 추정이 아니라 진짜 실행 결과
 * @param worstGap    추정 vs 실제 괴리가 가장 큰 최하위 노드(임계 미만이면 null)
 * @param rootCauses  매칭된 근본원인 목록(없으면 빈 리스트)
 * @param notes       정직성 안내(총량 환산·파싱 한계 등)
 */
public record DeepDiagnosis(String plan, CardinalityGap worstGap,
                            List<RootCause> rootCauses, List<String> notes) {

    /**
     * 카디널리티 오추정 노드 — 추정(estimatedRows) vs 실제(actualRows)의 괴리.
     *
     * <b>중요(오독 함정)</b>: MySQL·PostgreSQL의 실행계획에서 노드별 실제 행수는 loops당 <b>평균</b>이다.
     * 그래서 여기 actualRows·estimatedRows는 loops를 곱한 <b>총량</b>으로 환산해 담는다(loops·ratio 함께 제공).
     * Oracle의 A-Rows는 이미 누적 총량이라 곱하지 않는다(loops=1로 표기). ratio는 곱셈이 상쇄돼 총량이든
     * loops당이든 동일하다 — max(추정,실제)/min으로 계산한 배수다.
     *
     * @param node          노드 설명(연산·대상 테이블/컬렉션)
     * @param estimatedRows 옵티마이저 추정 행수(총량 환산)
     * @param actualRows    실제 행수(총량 환산 — loops당 평균 × loops)
     * @param loops         노드 반복 실행 횟수(총량 환산에 쓴 곱수. Oracle 등 총량 원본은 1)
     * @param ratio         괴리 배수 = max(추정,실제)/min(추정,실제)
     */
    public record CardinalityGap(String node, long estimatedRows, long actualRows,
                                 long loops, double ratio) {
    }

    /**
     * 인덱스 무력화 근본원인 1건 (증상이 아니라 원인).
     *
     * <b>표시 순서 원칙(외부 리뷰 반영)</b>: 근본원인이 있으면 화면에서 원인을 카디널리티 괴리보다
     * 먼저 보여준다 — 괴리는 원인의 <b>증상</b>이라, 증상이 첫 카드면 사용자가 "통계 갱신(ANALYZE)"
     * 같은 엉뚱한 처방으로 빠질 수 있다. 괴리는 원인을 못 찾았을 때만 헤드라인이 된다(그때는 유일한 단서).
     *
     * @param cause        근본원인 종류(암시적 형변환·컬럼에 함수·통계 노후·낮은 선택도·복합 선두 누락)
     * @param signal       이 원인으로 판정한 감지 신호(추정 vs 실제 괴리·타입 불일치·플랜 경고 등)
     * @param detail       왜 인덱스를 못 타는지와 방향 제시(정합성 위험 포함)
     * @param suggestedSql 기계적으로 안전한 수정안이 있을 때만(예: 숫자 리터럴에 따옴표) — 원클릭
     *                     재진단(before/after 검증 루프)용. 안전한 자동 수정이 없으면 null
     */
    public record RootCause(String cause, String signal, String detail, String suggestedSql) {
        /** 자동 수정안이 없는 원인용 — suggestedSql 없이 생성 */
        public RootCause(String cause, String signal, String detail) {
            this(cause, signal, detail, null);
        }
    }
}
