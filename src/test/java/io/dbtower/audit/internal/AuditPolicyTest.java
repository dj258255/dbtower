package io.dbtower.audit.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPolicyTest {

    @Test
    void 성공한_실행기_단계만_요청_단위_기록에서_빠진다() {
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/outbox/claim", 200)).isTrue();
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/0f8a/analyze", 200)).isTrue();
        // 누가 기계 경로를 두드렸는지는 남긴다
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/outbox/claim", 403)).isFalse();
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/0f8a/claim", 409)).isFalse();
        // 사람이 하는 접수·취소·재시도는 그대로 남는다
        assertThat(AuditPolicy.recordedByService("/api/ai-operations", 202)).isFalse();
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/0f8a/cancel", 200)).isFalse();
        assertThat(AuditPolicy.recordedByService("/api/ai-operations/0f8a/retry", 200)).isFalse();
    }
}
