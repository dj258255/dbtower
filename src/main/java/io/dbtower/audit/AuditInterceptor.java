package io.dbtower.audit;

import io.dbtower.audit.internal.AuditPolicy;
import io.dbtower.audit.internal.AuditRecorder;
import io.dbtower.audit.internal.domain.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/**
 * 상태 변경·진단 실행 요청의 감사 기록.
 *
 * 서블릿 필터가 아니라 HandlerInterceptor인 이유 — 인터셉터는 시큐리티 필터 체인이
 * 인증을 끝낸 뒤 DispatcherServlet 안에서 실행되므로 SecurityContextHolder에서
 * principal을 확실히 읽을 수 있다. 필터로 만들면 등록 순서(체인 내 위치)에 따라
 * 컨텍스트가 아직 비어 있을 수 있고, 그 순서는 코드만 봐서는 드러나지 않는 암묵적 계약이 된다.
 *
 * 한계와 보완 — 시큐리티 필터 단계에서 거부된 요청(403)은 DispatcherServlet에
 * 도달하지 않아 인터셉터가 볼 수 없다. 그 경우는 AuthorizationAuditListener가 기록한다.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final String START_ATTRIBUTE = AuditInterceptor.class.getName() + ".start";

    private final AuditRecorder recorder;

    public AuditInterceptor(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        return true;
    }

    /**
     * afterCompletion 시점에 기록하는 이유 — 핸들러 성공뿐 아니라
     * 예외가 @ExceptionHandler로 변환된 뒤의 최종 상태 코드(outcome)와 처리 시간까지 확정돼 있다.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!AuditPolicy.shouldRecord(request.getMethod(), request.getRequestURI())) {
            return;
        }
        // 여기 도달했다면 인가를 통과한 요청 — anyRequest().authenticated()라 인증 주체가 항상 있다
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = auth != null ? auth.getName() : "unknown";
        Object start = request.getAttribute(START_ATTRIBUTE);
        Long durationMs = start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : null;

        // 저장 실패는 AuditRecorder가 삼키고 error 로그만 남긴다 — 본 요청을 실패시키지 않기 위해
        recorder.record(new AuditEvent(
                LocalDateTime.now(),
                principal,
                AuditPolicy.roleOf(auth),
                request.getMethod() + " " + request.getRequestURI(),
                AuditPolicy.extractInstanceId(request.getRequestURI()),
                response.getStatus(),
                durationMs));
    }
}
