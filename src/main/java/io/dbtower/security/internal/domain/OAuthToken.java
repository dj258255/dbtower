package io.dbtower.security.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 발급된 OAuth 토큰 (V20) — access는 짧게(기본 1시간), refresh로 갱신한다. 재시작·다중 노드에서
 * 살아남아야 하므로 메타 DB에 둔다(공유 세션과 같은 이유). 폐기는 행 삭제.
 * username은 발급받은 사용자 — 실제 권한은 검증 시 platform_user에서 재조회한다(권한 변경 즉시 반영).
 */
@Entity
@Table(name = "oauth_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthToken {

    @Id
    @Column(name = "access_token", length = 96)
    private String accessToken;

    @Column(name = "refresh_token", length = 96, unique = true)
    private String refreshToken;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OAuthToken(String accessToken, String refreshToken, String clientId, String username,
                      LocalDateTime expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.clientId = clientId;
        this.username = username;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean expired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
