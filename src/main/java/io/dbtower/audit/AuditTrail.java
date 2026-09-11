package io.dbtower.audit;

import io.dbtower.audit.internal.AuditPolicy;
import io.dbtower.audit.internal.AuditRecorder;
import io.dbtower.audit.internal.domain.AuditEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * HTTP 요청 경계 안쪽에서 일어나는 행위를 서비스 코드가 직접 남기는 창구.
 *
 * AuditInterceptor는 요청 단위라 자연어 진단이면 "POST /diagnose" 한 줄만 남는다. 그 안에서 AI가
 * 부른 도구는 서비스 토큰으로 자기 REST를 다시 부르므로, 인터셉터에는 실제 사용자가 아니라
 * "api-token"으로 찍히거나(POST explain) 아예 찍히지 않는다(GET 도구). 누가 시킨 조회인지를
 * 이어 붙이려면 서비스가 호출 스레드의 인증 주체로 직접 기록해야 한다.
 */
@Component
public class AuditTrail {

    /** action 컬럼 길이(엔티티 length=500) — 넘기면 저장 자체가 실패해 기록이 통째로 사라진다. */
    private static final int ACTION_MAX = 500;

    private final AuditRecorder recorder;

    public AuditTrail(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    public void record(String action, Long instanceId, int outcome) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 봇 게이트웨이 스레드처럼 인증 컨텍스트가 없는 호출은 채널 화이트리스트가 경계다 — system으로 구분한다
        String principal = auth != null ? auth.getName() : "system";
        String bounded = action.length() > ACTION_MAX ? action.substring(0, ACTION_MAX) : action;
        recorder.record(new AuditEvent(LocalDateTime.now(), principal, AuditPolicy.roleOf(auth),
                bounded, instanceId, outcome, null));
    }
}
