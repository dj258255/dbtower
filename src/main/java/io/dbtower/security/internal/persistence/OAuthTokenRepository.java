package io.dbtower.security.internal.persistence;

import io.dbtower.security.internal.domain.OAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, String> {

    Optional<OAuthToken> findByRefreshToken(String refreshToken);

    /** 만료 토큰 정리(주기 스윕) — 재시작·다중 노드에서 쌓이는 죽은 토큰을 비운다. */
    int deleteByExpiresAtBefore(LocalDateTime cutoff);
}
