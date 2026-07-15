package io.dbtower.audit.internal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 기록 대상 판정과 공통 추출 로직 — 인터셉터(정상 처리)와 인가 거부 리스너(403)가
 * 같은 기준을 쓰도록 한 곳에 모은다. 기준이 갈라지면 "기록됐어야 할 요청"이 경로에 따라 새는데,
 * 감사 로그는 빠짐이 곧 결함이다.
 */
public final class AuditPolicy {

    private static final Set<String> RECORDED_METHODS = Set.of("POST", "PUT", "DELETE");
    private static final Pattern INSTANCE_PATH = Pattern.compile("/api/instances/(\\d+)(?:/.*)?");

    private AuditPolicy() {
    }

    /**
     * /api/** 의 POST/PUT/DELETE만 기록한다.
     * GET 조회는 기록하지 않는다 — 웹 UI 폴링·목록 조회가 빈도의 대부분이라
     * 감사 로그가 노이즈로 채워져 정작 봐야 할 상태 변경·진단 실행이 묻힌다.
     * explain·ai-analysis·backup처럼 대상 DB에 무언가를 "실행"하는 행위는 모두 POST라 이 기준에 걸린다.
     */
    public static boolean shouldRecord(String method, String path) {
        return path != null && path.startsWith("/api/") && RECORDED_METHODS.contains(method);
    }

    /** /api/instances/{id}/... 꼴이면 대상 인스턴스 id, 아니면 null — 인스턴스별 이력 추적용 */
    public static Long extractInstanceId(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = INSTANCE_PATH.matcher(path);
        return m.matches() ? Long.valueOf(m.group(1)) : null;
    }

    /** 권한명에서 ROLE_ 접두사를 벗겨 저장한다 — 조회하는 쪽이 스프링 시큐리티 관례를 몰라도 되게 */
    public static String roleOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        String joined = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }
}
