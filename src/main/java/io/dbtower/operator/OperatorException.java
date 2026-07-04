package io.dbtower.operator;

/** Operator 계층에서 발생한 오류. 대상 DB 접속 실패·권한 부족·통계 소스 미설정 등을 감싼다. */
public class OperatorException extends RuntimeException {

    public OperatorException(String message) {
        super(message);
    }

    public OperatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
