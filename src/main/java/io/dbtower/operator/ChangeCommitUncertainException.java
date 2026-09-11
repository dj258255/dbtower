package io.dbtower.operator;

/**
 * 변경 실행에서 커밋 호출 자체가 실패했다 — 대상 DB가 커밋을 반영했는지 클라이언트는 알 수 없다(네트워크 단절 등).
 * 일반 실패(롤백 확정)와 구분해야 호출자가 티켓을 "다시 실행 가능"으로 되돌리지 않고 사람의 확인을 기다리게 둔다.
 */
public class ChangeCommitUncertainException extends OperatorException {

    public ChangeCommitUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
