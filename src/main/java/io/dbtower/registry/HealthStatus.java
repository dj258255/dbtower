package io.dbtower.registry;

import java.util.Locale;

/** 헬스체크 결과. up 여부와 함께 버전·응답시간을 담아 대시보드에서 바로 쓴다. */
public record HealthStatus(boolean up, String version, long pingMillis, String message) {

    public static HealthStatus up(String version, long pingMillis) {
        return new HealthStatus(true, version, pingMillis, "OK");
    }

    /** 이미 사람이 읽는 문장인 사유를 그대로 싣는다(테스트·목). 접속 실패 원문에는 {@link #down(Throwable)}를 쓴다. */
    public static HealthStatus down(String message) {
        return new HealthStatus(false, null, -1, message);
    }

    /**
     * 실패 원인에서 <b>분류된 사유</b>만 뽑아 싣는다 — 이 값은 {@code GET /api/instances/{id}/health} 응답으로
     * 그대로 나가 화면의 "응답" 칸에 보인다. 드라이버 원문("Failed to obtain JDBC Connection: Connection refused")을
     * 그대로 실으면 화면에 영문 오류가 뜬다(B9). 원문은 호출자가 서버 로그에 남긴다(148절).
     */
    public static HealthStatus down(Throwable failure) {
        return new HealthStatus(false, null, -1, classify(failure));
    }

    /**
     * 분류 기준 — 사람이 읽는 네 갈래로만 접는다. 대상이 죽었을 때 운영자가 다음에 할 일이 갈리는 지점이다.
     * <ul>
     *   <li>"연결 거부" — 포트가 닫혔거나 호스트를 못 찾음(방화벽·프로세스 다운)</li>
     *   <li>"시간 초과" — 연결은 됐으나 응답이 없음(멈춘 프록시·죽은 포트 포워드)</li>
     *   <li>"인증 실패" — 계정·비밀번호·권한 문제</li>
     *   <li>"알 수 없음" — 그 밖(위 문구가 원인 사슬 어디에도 없는 경우)</li>
     * </ul>
     * 원인 사슬을 모두 훑는다 — 풀이 첫 연결을 늦게 열면 바깥 예외는 "커넥션을 못 얻었다"뿐이고 실제 사유는 끝에 있다(134절).
     */
    static String classify(Throwable failure) {
        if (failure == null) {
            return "알 수 없음";
        }
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            all.append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()).append(' ');
        }
        return classify(all.toString());
    }

    static String classify(String raw) {
        String m = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (m.contains("refused") || m.contains("connectexception") || m.contains("unknownhost")
                || m.contains("unknown host") || m.contains("no route") || m.contains("network is unreachable")) {
            return "연결 거부";
        }
        if (m.contains("timed out") || m.contains("timeout") || m.contains("timedout")) {
            return "시간 초과";
        }
        if (m.contains("password") || m.contains("access denied") || m.contains("authentication")
                || m.contains("login failed") || m.contains("not authorized") || m.contains("인증")) {
            return "인증 실패";
        }
        return "알 수 없음";
    }
}
