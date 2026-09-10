package io.dbtower.operator.model;

import java.util.List;

/**
 * 변경 실행 결과.
 *
 * @param committed           커밋했는가(드라이런은 항상 false)
 * @param affectedRows        대상 DB가 보고한 영향 행 수
 * @param before              변경 전 행 사본(INSERT는 빈 사본, DDL·캡처 없음이면 null)
 * @param after               변경 후 행 사본(DELETE는 빈 사본, 키가 없거나 DDL이면 null)
 * @param rollbackUnavailable 되돌리기 경로가 없으면 그 이유(있으면 null)
 * @param probeBefore         변경 직전 검증 조회 측정(검증 조회가 없으면 null)
 * @param probeAfter          변경 직후 같은 트랜잭션 안의 측정
 * @param elapsedMs           전체 소요
 */
public record ChangeOutcome(boolean committed, long affectedRows, RowImage before, RowImage after,
                            String rollbackUnavailable, Probe probeBefore, Probe probeAfter, long elapsedMs) {

    /**
     * 검증 조회 한 번의 측정 — 실행계획 원문과 반복 실행 응답시간.
     *
     * @param plan          기종의 EXPLAIN 출력(텍스트)
     * @param timingsMicros 반복 실행 각각의 소요(마이크로초, 결과는 끝까지 읽는다). 작은 표는 밀리초로 재면 전부 0이 된다
     * @param error         측정 실패 이유(성공이면 null) — 측정 실패가 변경 자체를 막지는 않는다
     */
    public record Probe(String plan, List<Long> timingsMicros, String error) {
    }
}
