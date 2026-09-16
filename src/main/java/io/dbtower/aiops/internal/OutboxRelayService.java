package io.dbtower.aiops.internal;

import io.dbtower.aiops.internal.persistence.AiOperationOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Outbox를 외부 릴레이에 넘기는 창구. 릴레이는 선점한 이벤트를 큐에 넣고 발행 완료를 알린다.
 *
 * <p>발행 완료를 알리기 전에 릴레이가 죽으면 리스 만료 뒤 다른 릴레이가 같은 이벤트를 다시 가져간다 — 유실보다 중복을 택했다.
 * 중복 배달은 작업 선점(RECEIVED -> AUTHORIZED)이 한 번만 성공해 흡수한다.</p>
 */
@Service
public class OutboxRelayService {

    public record Claimed(String eventId, String jobId, String eventType, String payload, String claimToken,
                          int attempts) {
    }

    private final AiOperationOutboxRepository outbox;
    private final AiOperationSettings settings;
    private final Clock clock = Clock.systemDefaultZone();

    public OutboxRelayService(AiOperationOutboxRepository outbox, AiOperationSettings settings) {
        this.outbox = outbox;
        this.settings = settings;
    }

    @Transactional
    public List<Claimed> claim(int limit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime until = now.plus(settings.outboxClaim());
        String token = UUID.randomUUID().toString();
        List<Claimed> claimed = new ArrayList<>();
        for (String eventId : outbox.findClaimable(now, PageRequest.of(0, Math.max(1, Math.min(100, limit))))) {
            // 후보를 읽은 뒤 다른 릴레이가 먼저 가져갔으면 갱신 행이 0이다 — 그 이벤트는 건너뛴다
            if (outbox.claim(eventId, token, now, until) == 1) {
                outbox.findById(eventId).ifPresent(o -> claimed.add(new Claimed(o.getEventId(), o.getJobId(),
                        o.getEventType(), o.getPayload(), token, o.getAttempts())));
            }
        }
        return claimed;
    }

    /** 토큰이 맞지 않으면 false — 리스가 만료돼 다른 릴레이가 가져간 이벤트다. */
    @Transactional
    public boolean markPublished(String eventId, String claimToken) {
        return outbox.markPublished(eventId, claimToken, OffsetDateTime.now(clock)) == 1;
    }
}
