package io.dbtower.operator.model;

import java.util.List;

/**
 * 대량 일괄 변경의 배치 하나가 남긴 것. 배치마다 이 기록이 하나씩 쌓여 "어디까지 적용됐는가"의 유일한 근거가 된다 —
 * 취소·실패가 커밋된 배치를 되돌리지 않으므로(docs/design/bulk-change-spec.md), 마지막 키가 곧 재개 지점이자 사람이
 * 백업 복원을 판단할 자리다.
 *
 * @param fromKey       이 배치가 연 구간의 하한(첫 배치는 null — 하한 없이 시작한다). 복합 키는 키 순서대로
 * @param toKey         이 배치가 닫은 구간의 상한. 다음 배치의 하한이 된다
 * @param affectedRows  실제로 바뀐 행 수. 구간 안에 조건에 맞는 행이 없으면 0이고, 그것도 정상이다
 * @param elapsedMillis 배치 문장 실행에 걸린 시간
 */
public record BulkBatchOutcome(List<Object> fromKey, List<Object> toKey, long affectedRows, long elapsedMillis) {

    /** 사람이 읽고 기록에 남길 모양 — 단일 키는 값 하나, 복합 키는 {@code (12, 340)}. */
    public static String render(List<Object> key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (key.size() == 1) {
            return String.valueOf(key.get(0));
        }
        return "(" + key.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ")";
    }
}
