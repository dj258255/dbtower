package io.dbtower.registry;

import java.time.LocalDateTime;

/** 콘솔 계정 요약 — API 응답용이라 비밀번호를 담지 않는다. */
public record CredentialSummary(CredentialPurpose purpose, String username, LocalDateTime updatedAt) {
}
