package io.dbtower.insight.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 쿼리 지문 — 원문 대신 남기는 32자 해시.
 *
 * <p>원문을 저장하지 않는 이유 둘. (1) 2000자 절단본을 초당 수천 행 쌓으면 부피가 폭증한다.
 * (2) 파라미터에 개인정보가 실려 올 수 있고, 관측 데이터는 대개 원본보다 접근 권한이 느슨하다.
 *
 * <p>정규화는 의도적으로 얕게만 한다. 완전한 SQL 파싱은 이 자리의 목적(같은 모양의 쿼리를
 * 같은 키로 묶기)에 과하고, 파서를 태우면 샘플러가 무거워져 A9 원칙과 충돌한다. 얕은 정규화라
 * 지문 충돌이 생길 수 있음을 전제로 쓴다 — 충돌은 하류에서 SUM으로 접는다.
 */
public final class QueryFingerprint {

    private QueryFingerprint() {
    }

    public static String of(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query
                .toLowerCase()
                .replaceAll("'[^']*'", "?")   // 문자열 리터럴
                .replaceAll("\\b\\d+\\b", "?") // 숫자 리터럴
                .replaceAll("\\s+", " ")
                .trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            // 앞 16바이트(32헥스)면 충돌 확률이 이 용도에 충분히 낮다.
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e); // JDK 표준이라 도달 불가
        }
    }
}
