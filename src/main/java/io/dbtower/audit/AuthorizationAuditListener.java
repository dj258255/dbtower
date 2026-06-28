package io.dbtower.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 시큐리티 필터 단계에서 거부된 요청(403)의 감사 기록.
 *
 * requestMatchers 인가 거부는 DispatcherServlet 앞(AuthorizationFilter)에서 끝나므로
 * AuditInterceptor가 볼 수 없다 — "VIEWER가 인스턴스 삭제를 시도했다" 같은 월권 시도가
 * 감사에서 통째로 빠지게 된다. 그 사각을 인가 거부 이벤트로 메운다.
 * 인가를 통과한 요청은 인터셉터가, 거부된 요청은 이 리스너가 기록하므로 중복은 없다.
 */
@Component
public class AuthorizationAuditListener {

    private final AuditRecorder recorder;

    public AuthorizationAuditListener(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        // HTTP 요청 인가 거부만 대상 (메서드 시큐리티 등 다른 대상은 요청 문맥이 없다).
        // AuthorizationFilter는 보안 대상 객체로 HttpServletRequest 자체를 실어 보내고(실측),
        // 매처 단위 인가는 RequestAuthorizationContext로 감싸 보낼 수 있어 둘 다 받는다.
        HttpServletRequest request = switch (event.getObject()) {
            case HttpServletRequest r -> r;
            case RequestAuthorizationContext c -> c.getRequest();
            default -> null;
        };
        if (request == null) {
            return;
        }
        if (!AuditPolicy.shouldRecord(request.getMethod(), request.getRequestURI())) {
            return;
        }
        Authentication auth = event.getAuthentication().get();
        // 익명(미인증) 거부는 ExceptionTranslationFilter가 401 로그인 유도로 바꾼다 —
        // 감사 대상은 "인증된 주체의 월권(403)"이지 무작위 비인증 접근이 아니다
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return;
        }
        // 요청이 핸들러에 도달하지 않았으므로 처리 시간은 의미가 없다 — durationMs=null
        recorder.record(new AuditEvent(
                LocalDateTime.now(),
                auth.getName(),
                AuditPolicy.roleOf(auth),
                request.getMethod() + " " + request.getRequestURI(),
                AuditPolicy.extractInstanceId(request.getRequestURI()),
                HttpServletResponse.SC_FORBIDDEN,
                null));
    }
}
