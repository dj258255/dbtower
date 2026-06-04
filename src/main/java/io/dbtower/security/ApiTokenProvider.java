package io.dbtower.security;

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
 * 미설정 시 기동마다 랜덤 생성하고 로그로 알린다(Jenkins 초기 비밀번호 방식) —
 * "설정 안 하면 무인증"이 아니라 "설정 안 하면 아무도 모르는 토큰"이 기본값이 되게 한다(fail-closed).
 */
@Component
public class ApiTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenProvider.class);

    private final String token;

    public ApiTokenProvider(@Value("${dbtower.security.api-token:}") String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            this.token = HexFormat.of().formatHex(bytes);
            log.info("DBTOWER_API_TOKEN 미설정 — 이번 기동용 토큰을 생성했습니다 (MCP 연동 카드에서 확인 가능)");
        } else {
            this.token = configured;
        }
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
