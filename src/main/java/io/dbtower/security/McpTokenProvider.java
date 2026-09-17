package io.dbtower.security;

import io.dbtower.security.internal.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * MCP 전용 서비스 토큰(#99). 인증되는 자리는 {@link McpTokenScope}가 정한다.
 *
 * <p>API 토큰과 같은 규칙으로 만든다: 명시 설정(DBTOWER_MCP_TOKEN)이 있으면 그것, 없으면 메타 DB에 한 번 만들어 둔 값(재시작 생존).
 * 전에는 API 토큰 하나가 REST 전체와 MCP에 같이 통해, MCP 클라이언트 설정에 넣은 토큰이 새면 관리 API까지 열렸다.
 */
@Component
public class McpTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(McpTokenProvider.class);

    static final String TOKEN_SETTING_KEY = "mcp-token";

    private final String token;

    public McpTokenProvider(@Value("${dbtower.security.mcp-token:}") String configured, SettingStore settings) {
        if (configured == null || configured.isBlank()) {
            this.token = settings.getOrCreate(TOKEN_SETTING_KEY, McpTokenProvider::randomToken);
            log.info("DBTOWER_MCP_TOKEN 미설정 — 저장된 MCP 토큰을 사용합니다(없으면 생성·저장). MCP 연동 카드에서 확인 가능");
        } else {
            this.token = configured;
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String token() {
        return token;
    }

    /** 타이밍 공격 방지를 위해 상수 시간 비교 */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
