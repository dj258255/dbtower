package io.dbtower.audit;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 성공/실패 이벤트 리스너 단위 테스트 — 스프링 컨텍스트 없이
 * "어떤 이벤트가 어떤 감사 행이 되나"만 고정한다.
 */
class AuthenticationAuditListenerTest {

    private final List<AuditEvent> saved = new ArrayList<>();

    /** 저장소 대신 기록만 가로채는 recorder — 리스너의 관심사는 저장이 아니라 이벤트 -> 행 변환이다 */
    private final AuditRecorder capturing = new AuditRecorder(null) {
        @Override
        public void record(AuditEvent event) {
            saved.add(event);
        }
    };

    private final AuthenticationAuditListener listener = new AuthenticationAuditListener(capturing);

    @Test
    void 로그인_성공은_principal과_역할이_200으로_남는다() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        listener.onSuccess(new AuthenticationSuccessEvent(auth));

        assertThat(saved).hasSize(1);
        AuditEvent event = saved.get(0);
        assertThat(event.getAction()).isEqualTo("LOGIN");
        assertThat(event.getPrincipal()).isEqualTo("admin");
        assertThat(event.getRole()).isEqualTo("ADMIN");
        assertThat(event.getOutcome()).isEqualTo(200);
        assertThat(event.getDurationMs()).isNull();
        assertThat(event.getInstanceId()).isNull();
    }

    @Test
    void 로그인_실패는_시도한_계정명이_401로_남는다() {
        Authentication attempt = new UsernamePasswordAuthenticationToken("intruder", "wrong");

        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                attempt, new BadCredentialsException("자격 증명 불일치")));

        assertThat(saved).hasSize(1);
        AuditEvent event = saved.get(0);
        assertThat(event.getAction()).isEqualTo("LOGIN");
        assertThat(event.getPrincipal()).isEqualTo("intruder");
        assertThat(event.getRole()).isNull();
        assertThat(event.getOutcome()).isEqualTo(401);
    }
}
