package io.dbtower.operator.model;

import io.dbtower.operator.model.ChangePlan.Kind;

import java.util.List;

/**
 * 커밋된 변경을 행 사본으로 되돌리는 계획. 되돌리기 전에 현재 행이 실행 직후 사본과 같은지 먼저 대조한다 —
 * 그 사이 누가 같은 행을 바꿨다면 덮어쓰지 않고 충돌로 돌려준다.
 */
public record RevertPlan(Kind originalKind, String table, RowImage before, RowImage after,
                         int timeoutSeconds, boolean dryRun) {

    /**
     * 실행 이후 달라진 행 하나.
     *
     * @param keyValues      기본 키 값(keyColumns 순서)
     * @param changedColumns 실행 직후 사본과 값이 달라진 열 이름(값은 싣지 않는다)
     * @param reason         달라진 모양(값이 바뀜·행이 사라짐·같은 키 행이 다시 생김)
     */
    public record Conflict(List<String> keyValues, List<String> changedColumns, String reason) {
    }

    /**
     * @param committed    되돌리기를 커밋했는가(드라이런·충돌이면 false)
     * @param restoredRows 되돌린 행 수
     * @param conflicts    실행 이후 달라진 행 — 하나라도 있으면 아무것도 되돌리지 않는다
     * @param elapsedMs    소요
     */
    public record Outcome(boolean committed, long restoredRows, List<Conflict> conflicts, long elapsedMs) {
    }
}
