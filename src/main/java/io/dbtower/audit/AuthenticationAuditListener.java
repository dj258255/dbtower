package io.dbtower.audit;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 로그인 성공/실패의 감사 기록.
 *
 * 로그인 처리(/login)는 시큐리티 필터 체인 안에서 끝나 인터셉터에 잡히지 않으므로
 * 인증 이벤트로 기록한다. outcome은 요청 감사와 축을 맞춰 HTTP 상태로 환산한다(성공 200 / 실패 401).
 */
@Component
public class AuthenticationAuditListener {

    /** 로그인은 특정 API 경로가 아니라 행위 자체가 대상이라 action을 고정 문자열로 남긴다 */
    static final String LOGIN_ACTION = "LOGIN";

    private final AuditRecorder recorder;

    public AuthenticationAuditListener(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        // 폼 로그인 한 번에 ProviderManager의 AuthenticationSuccessEvent와
        // 인증 처리 필터의 InteractiveAuthenticationSuccessEvent가 연달아 발행되지만,
        // 시큐리티 7부터 후자는 이 타입의 하위가 아니라 별개 형제 타입이라 이 리스너에 오지 않는다
        // — 한 로그인당 정확히 한 줄이 남는다.
        Authentication auth = event.getAuthentication();
        recorder.record(new AuditEvent(LocalDateTime.now(), auth.getName(), AuditPolicy.roleOf(auth),
                LOGIN_ACTION, null, HttpServletResponse.SC_OK, null));
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        // 실패 시점에는 역할이 확정되지 않으므로 role=null — 시도한 계정명(principal)만 남긴다
        recorder.record(new AuditEvent(LocalDateTime.now(), event.getAuthentication().getName(), null,
                LOGIN_ACTION, null, HttpServletResponse.SC_UNAUTHORIZED, null));
    }
}
