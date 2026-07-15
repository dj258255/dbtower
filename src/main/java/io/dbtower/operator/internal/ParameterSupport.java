package io.dbtower.operator.internal;

import io.dbtower.operator.model.DbParameter;

import java.util.List;
import java.util.Locale;

/**
 * parameters() 공통 보조 로직 (B6). 기종별 Operator는 이름/값을 SQL·명령으로 뽑아 of()에 넘기고,
 * 민감 파라미터 마스킹은 여기 한 곳에서 한다 — 5기종이 각자 마스킹 규칙을 갖지 않게 모은다.
 *
 * 파라미터에는 자격증명이 섞일 수 있다(예: MySQL의 각종 *_password, ssl 키 파일 경로 등).
 * 이름에 아래 조각이 들어가면 값을 마스킹한다. 이름 기반 휴리스틱이라 완전하지 않다는 점이
 * 곧 REST를 ADMIN으로 제한하는 근거다(ParameterController 주석 참고).
 */
final class ParameterSupport {

    /** 마스킹 대체 토큰 — 좌우 동일하므로 diff에서 '변경'으로 잡히지 않는다 */
    static final String MASKED = "***";

    /** 이름에 포함되면 민감으로 보는 조각(소문자 비교) */
    private static final List<String> SENSITIVE = List.of(
            "password", "passwd", "pwd", "secret", "credential",
            "private_key", "privatekey", "ssl_key", "sslkey", "keyfile", "key_file");

    private ParameterSupport() {
    }

    static boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SENSITIVE.stream().anyMatch(lower::contains);
    }

    /** 민감 이름이면 값 대신 마스킹 토큰을, 아니면 값을(null이면 빈 문자열) 담는다 */
    static DbParameter of(String name, String value, String unit) {
        if (isSensitive(name)) {
            return new DbParameter(name, MASKED, unit);
        }
        return new DbParameter(name, value == null ? "" : value, unit);
    }
}
