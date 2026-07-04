package io.dbtower.onlineddl;

/**
 * 온라인 스키마 변경 결과 (B4) — A7 복원 검증의 3-값 정직성 원칙을 그대로 따른다.
 *
 * 상태는 3-값이다:
 * - OK          : dry-run(noop)이 통과했거나 실제 실행(execute)이 완료됐다.
 * - FAILED      : gh-ost가 에러로 끝났다 — stderr 요약(detail)으로 원인을 남긴다.
 * - UNSUPPORTED : 비 MySQL 기종이거나 gh-ost 바이너리가 없어 플랫폼이 수행할 수 없다.
 *                 "성공"이 아니라 "할 수 없음"이다 — OK로 위장하지 않는 것이 이 값의 존재 이유.
 *
 * mode는 실제로 무엇을 했는지("noop" 또는 "execute")를, ghostTable은 gh-ost가 만든 고스트 테이블 이름을
 * (판별 가능한 경우) 담는다. 그 외에는 null.
 */
public record OnlineDdlResult(Status status, String mode, String detail, String ghostTable) {

    public enum Status { OK, FAILED, UNSUPPORTED }

    static OnlineDdlResult ok(String mode, String detail, String ghostTable) {
        return new OnlineDdlResult(Status.OK, mode, detail, ghostTable);
    }

    static OnlineDdlResult failed(String mode, String detail) {
        return new OnlineDdlResult(Status.FAILED, mode, detail, null);
    }

    static OnlineDdlResult unsupported(String detail) {
        return new OnlineDdlResult(Status.UNSUPPORTED, null, detail, null);
    }
}
