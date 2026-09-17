package io.dbtower.registry;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다운 사유 분류 (B9) — {@code GET /api/instances/{id}/health} 응답의 message는 화면의 "응답" 칸에 그대로 보인다.
 *
 * <p>전에는 드라이버 원문("Failed to obtain JDBC Connection: Connection refused")이 그대로 내려가 화면에
 * 영문 오류가 떴다. 원문은 서버 로그로만 남기고, 응답에는 사람이 읽는 네 갈래(연결 거부·시간 초과·인증 실패·알 수 없음)만 싣는다.
 */
class HealthStatusReasonTest {

    @Test
    void 드라이버_영문_원문은_분류된_사유로만_남는다() {
        HealthStatus refused = HealthStatus.down(new SQLException(
                "Failed to obtain JDBC Connection: Connection refused"));

        assertThat(refused.up()).isFalse();
        assertThat(refused.message()).isEqualTo("연결 거부");
        assertThat(refused.message()).doesNotContain("Connection refused");
    }

    @Test
    void 원인_사슬_끝의_사유까지_본다() {
        // 풀이 첫 연결을 늦게 열면(134절) 바깥 예외는 "커넥션을 못 얻었다"뿐이고 실제 사유는 원인 끝에 있다
        RuntimeException outer = new RuntimeException("Failed to obtain JDBC Connection",
                new SQLException("Connection timed out"));

        assertThat(HealthStatus.down(outer).message()).isEqualTo("시간 초과");
    }

    @Test
    void 인증_실패와_알_수_없음으로_접힌다() {
        assertThat(HealthStatus.down(new SQLException("Access denied for user 'monitor'@'10.0.0.1'")).message())
                .isEqualTo("인증 실패");
        assertThat(HealthStatus.down(new RuntimeException("boom")).message()).isEqualTo("알 수 없음");
        assertThat(HealthStatus.down((Throwable) null).message()).isEqualTo("알 수 없음");
    }

    @Test
    void 메시지가_없으면_예외_종류로_가른다() {
        assertThat(HealthStatus.down(new ConnectException()).message()).isEqualTo("연결 거부");
    }

    @Test
    void 이미_사람이_읽는_사유는_그대로_둔다() {
        // 목·테스트가 쓰는 문자열 경로 — 알림 문구처럼 이미 한국어인 사유를 "알 수 없음"으로 뭉개지 않는다
        assertThat(HealthStatus.down("접속 불가").message()).isEqualTo("접속 불가");
    }
}
