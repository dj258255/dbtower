package io.dbtower;

import io.dbtower.registry.InstanceNotFoundException;
import io.dbtower.operator.OperatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 잘못된 요청(스냅샷 부족, SELECT 아닌 explain 등) */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", message(e));
    }

    /**
     * 인스턴스 없음(Phase 3) — 미등록과 팀 스코프 밖(LBAC)이 같은 404·같은 메시지다.
     * 403으로 구분하면 "그 id가 존재한다"는 사실이 새기 때문(존재 노출 방지).
     */
    @ExceptionHandler(InstanceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> instanceNotFound(InstanceNotFoundException e) {
        return Map.of("error", message(e));
    }

    /**
     * 대상 DB 접속·조회 실패 — 플랫폼 문제가 아니라 대상 문제임을 502로 구분한다.
     *
     * <p>A-4: OperatorException.getMessage()에는 대상 DB가 돌려준 원문(스키마·객체명 등)이 섞일 수 있어
     * 응답 본문으로 그대로 노출하면 정보가 샌다(CWE-209). 상세는 에러 ID와 함께 서버 로그에만 남기고,
     * 응답에는 일반화된 메시지 + 조회용 에러 ID만 준다(자격증명은 원래 메시지에 실리지 않는다).
     */
    @ExceptionHandler(OperatorException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> operatorFailure(OperatorException e) {
        String errorId = UUID.randomUUID().toString();
        log.warn("대상 DB 조회 실패 errorId={}", errorId, e);
        return Map.of(
                "error", "대상 데이터베이스 조회에 실패했습니다. 서버 로그를 확인하세요.",
                "errorId", errorId);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, String> notImplemented(UnsupportedOperationException e) {
        return Map.of("error", message(e));
    }

    /**
     * {@code Map.of}는 null 값에 NPE를 던진다 — 메시지 없는 예외
     * ({@code new IllegalArgumentException()})가 올라오면 의도한 400/404/501 대신 핸들러 자신이
     * 터져 500이 나간다. Spring·Jackson·JDBC 드라이버가 던지는 예외는 통제 밖이라 방어가 필요하다.
     */
    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
