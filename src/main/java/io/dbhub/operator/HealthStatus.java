package io.dbhub.operator;

/** 헬스체크 결과. up 여부와 함께 버전·응답시간을 담아 대시보드에서 바로 쓴다. */
public record HealthStatus(boolean up, String version, long pingMillis, String message) {

    public static HealthStatus up(String version, long pingMillis) {
        return new HealthStatus(true, version, pingMillis, "OK");
    }

    public static HealthStatus down(String message) {
        return new HealthStatus(false, null, -1, message);
    }
}
