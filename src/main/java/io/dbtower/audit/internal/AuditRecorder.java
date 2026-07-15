package io.dbtower.audit.internal;

import io.dbtower.audit.internal.domain.AuditEvent;
import io.dbtower.audit.internal.persistence.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 감사 이벤트 저장의 단일 관문. 저장 실패를 여기서 삼킨다 —
 * 감사는 본 기능의 부수 기록이지 선행 조건이 아니므로, 메타 DB 장애가
 * 백업·등록 같은 본 요청까지 실패시키면 안 된다. 대신 error 로그로 유실을 드러낸다.
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditEventRepository repository;

    public AuditRecorder(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(AuditEvent event) {
        try {
            repository.save(event);
        } catch (RuntimeException e) {
            log.error("감사 이벤트 저장 실패 (본 요청에는 영향 없음): action={} principal={}",
                    event.getAction(), event.getPrincipal(), e);
        }
    }
}
