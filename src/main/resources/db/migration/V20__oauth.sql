-- V20: MCP OAuth 2.1 인가 서버 (RFC 6749/7591 + PKCE) — MCP 클라이언트가 정적 Bearer 토큰 대신
-- "브라우저 로그인 → 자동 토큰 발급" 플로우를 쓰게 한다. DBTower의 기존 로그인·유저 위에 얹는다.
--
-- 인가 코드는 60초 일회용이라 메모리에 두고(HA 한계는 OAuthService 주석), 클라이언트 등록과
-- 발급 토큰만 영속한다 — 토큰은 재시작·다중 노드에서 살아남아야 한다(세션 공유와 같은 이유).

-- 동적 클라이언트 등록(RFC 7591) — Claude 등 MCP 클라이언트가 스스로 등록한다(공개 클라이언트, 시크릿 없음)
CREATE TABLE oauth_client (
    client_id     VARCHAR(64)  PRIMARY KEY,
    client_name   VARCHAR(200),
    redirect_uris TEXT         NOT NULL,   -- 개행 구분(등록 시 화이트리스트 — 리다이렉트 오픈 방지)
    created_at    TIMESTAMP    NOT NULL
);

-- 발급 토큰 — access는 짧게(기본 1시간), refresh로 갱신. 폐기(로그아웃·회전)는 행 삭제.
CREATE TABLE oauth_token (
    access_token  VARCHAR(96)  PRIMARY KEY,
    refresh_token VARCHAR(96)  UNIQUE,
    client_id     VARCHAR(64)  NOT NULL,
    username      VARCHAR(100) NOT NULL,   -- 발급받은 사용자(권한은 검증 시 platform_user에서 재조회)
    expires_at    TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_oauth_token_refresh ON oauth_token (refresh_token);
CREATE INDEX idx_oauth_token_expires ON oauth_token (expires_at);
