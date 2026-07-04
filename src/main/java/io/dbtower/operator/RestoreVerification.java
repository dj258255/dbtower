package io.dbtower.operator;

/**
 * 백업의 "복원 가능성" 검증 결과 (A7) — "테스트해 본 적 없는 백업은 백업이 아니다".
 *
 * 상태는 3-값이다. 기종·백업 모드마다 플랫폼이 실제로 확인할 수 있는 수준이 다르다는 현실을
 * 그대로 드러내기 위해서다:
 * - VERIFIED    : 임시/격리 대상에 실제로 복원해 보고(또는 서버가 백업셋 판독을 확인해) 성공했다.
 * - FAILED      : 복원/검증을 시도했으나 실패했다 — 이 백업은 신뢰할 수 없다.
 * - UNSUPPORTED : 기종/모드상 플랫폼이 자동으로 검증할 수 없다(예: 서버 사이드 산출물에 파일 접근 불가).
 *                 통과가 아니라 "확인 못 함"이다 — VERIFIED로 위장하지 않는 것이 이 값의 존재 이유.
 *
 * restoredObjectCount는 실제 복원까지 한 경우에만 채운다(복원된 테이블/컬렉션 수). 그 외에는 null.
 */
public record RestoreVerification(Status status, String detail, Integer restoredObjectCount) {

    public enum Status { VERIFIED, FAILED, UNSUPPORTED }

    static RestoreVerification verified(String detail, Integer restoredObjectCount) {
        return new RestoreVerification(Status.VERIFIED, detail, restoredObjectCount);
    }

    static RestoreVerification failed(String detail) {
        return new RestoreVerification(Status.FAILED, detail, null);
    }

    static RestoreVerification unsupported(String detail) {
        return new RestoreVerification(Status.UNSUPPORTED, detail, null);
    }
}
