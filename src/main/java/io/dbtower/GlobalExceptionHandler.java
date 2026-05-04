package io.dbtower;

import io.dbtower.operator.OperatorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 잘못된 요청(미등록 인스턴스, 스냅샷 부족, SELECT 아닌 explain 등) */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /** 대상 DB 접속·조회 실패 — 플랫폼 문제가 아니라 대상 문제임을 502로 구분한다 */
    @ExceptionHandler(OperatorException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> operatorFailure(OperatorException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, String> notImplemented(UnsupportedOperationException e) {
        return Map.of("error", e.getMessage());
    }
}
