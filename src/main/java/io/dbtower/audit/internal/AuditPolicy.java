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
    private static final Pattern INSTANCE_PATH = Pattern.compile("/api/(?:workbench/)?instances/(\\d+)(?:/.*)?");
    /** AI 운영 작업의 릴레이·실행기 단계 경로(169절) */
    private static final Pattern EXECUTOR_STEP = Pattern.compile(
            "/api/ai-operations/(?:outbox/.*|[^/]+/(?:claim|facts|retrieving|analyze|fail|notified))");

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

    /**
     * 요청 단위로 남기지 않아도 되는 성공한 기계 호출인가 — AI 운영 작업의 릴레이·실행기 단계.
     *
     * <p>릴레이는 Outbox를 매초 선점해 본다. 요청 단위로 남기자 기동 20초 만에 빈 선점 기록 19행이 쌓였다(169절) — 하루면 8만 행이
     * 감사 로그를 덮는다. 이 단계의 의미 있는 사건(선점·사실 수집·완료·실패)은 AiOperationWorkflow가 작업 id와 함께 직접 남긴다.
     * 성공 응답만 뺀다: 거부(403)·충돌(409)·오류는 누가 기계 경로를 두드렸는지의 흔적이라 그대로 남긴다.</p>
     */
    public static boolean recordedByService(String path, int status) {
        return status < 400 && path != null && EXECUTOR_STEP.matcher(path).matches();
    }

    /** /api/instances/{id}/... 또는 /api/workbench/instances/{id}/... 꼴이면 대상 인스턴스 id, 아니면 null — 인스턴스별 이력 추적용 */
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
