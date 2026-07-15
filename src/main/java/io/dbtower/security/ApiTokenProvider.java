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
 * API/MCP 서비스 토큰. 사람은 세션 로그인, 기계(AI 에이전트·자동화)는 이 토큰으로 인증한다.
 *
 * 우선순위: 명시 설정(DBTOWER_API_TOKEN)이 있으면 그것을 쓴다. 없으면 랜덤 생성하되 플랫폼 메타 DB에
 * 저장해 재시작에 살아남게 한다(Phase 1) — 예전엔 매 기동 새로 만들어 MCP 연동이 재시작마다 깨졌다.
 * "설정 안 하면 무인증"이 아니라 "설정 안 하면 아무도 모르지만 안정적인 토큰"이 기본값이다(fail-closed).
 */
@Component
public class ApiTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenProvider.class);

    static final String TOKEN_SETTING_KEY = "api-token";

    private final String token;

    public ApiTokenProvider(@Value("${dbtower.security.api-token:}") String configured,
                            SettingStore settings) {
        if (configured == null || configured.isBlank()) {
            this.token = settings.getOrCreate(TOKEN_SETTING_KEY, ApiTokenProvider::randomToken);
            log.info("DBTOWER_API_TOKEN 미설정 — 저장된 토큰을 사용합니다(없으면 생성·저장, 재시작 생존). "
                    + "MCP 연동 카드에서 확인 가능");
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
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
