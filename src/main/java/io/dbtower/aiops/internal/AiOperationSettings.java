package io.dbtower.aiops.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** AI 운영 작업의 운영 한도. 값의 이유는 application.yml의 dbtower.aiops 주석에 둔다. */
@Component
public class AiOperationSettings {

    private final int maxActivePerRequester;
    private final int maxActiveAlertJobs;
    private final Duration lease;
    private final Duration receivedTimeout;
    private final Duration outboxClaim;
    private final int defaultWindowMinutes;

    public AiOperationSettings(@Value("${dbtower.aiops.max-active-per-requester:3}") int maxActivePerRequester,
                               @Value("${dbtower.aiops.max-active-alert-jobs:3}") int maxActiveAlertJobs,
                               @Value("${dbtower.aiops.lease-seconds:300}") long leaseSeconds,
                               @Value("${dbtower.aiops.received-timeout-minutes:30}") long receivedTimeoutMinutes,
                               @Value("${dbtower.aiops.outbox-claim-seconds:60}") long outboxClaimSeconds,
                               @Value("${dbtower.aiops.default-window-minutes:60}") int defaultWindowMinutes) {
        this.maxActivePerRequester = maxActivePerRequester;
        this.maxActiveAlertJobs = maxActiveAlertJobs;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.receivedTimeout = Duration.ofMinutes(receivedTimeoutMinutes);
        this.outboxClaim = Duration.ofSeconds(outboxClaimSeconds);
        this.defaultWindowMinutes = defaultWindowMinutes;
    }

    public int maxActivePerRequester() { return maxActivePerRequester; }
    public int maxActiveAlertJobs() { return maxActiveAlertJobs; }
    public Duration lease() { return lease; }
    public Duration receivedTimeout() { return receivedTimeout; }
    public Duration outboxClaim() { return outboxClaim; }
    public int defaultWindowMinutes() { return defaultWindowMinutes; }
}
