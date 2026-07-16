package io.dbtower.security.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동적으로 등록된 OAuth 클라이언트 (RFC 7591, V20) — MCP 클라이언트(Claude 등)가 스스로 등록한다.
 * PKCE 공개 클라이언트라 시크릿이 없다 — 인증은 PKCE(code_verifier)와 등록된 redirect_uri로 한다.
 */
@Entity
@Table(name = "oauth_client")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthClient {

    @Id
    @Column(name = "client_id", length = 64)
    private String clientId;

    @Column(name = "client_name", length = 200)
    private String clientName;

    /** 등록된 redirect_uri 화이트리스트(개행 구분) — 인가 응답을 이 주소로만 보낸다(오픈 리다이렉트 방지). */
    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    private String redirectUris;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OAuthClient(String clientId, String clientName, List<String> redirectUris) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.redirectUris = String.join("\n", redirectUris);
        this.createdAt = LocalDateTime.now();
    }

    public List<String> redirectUriList() {
        return List.of(redirectUris.split("\n"));
    }
}
