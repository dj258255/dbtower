package io.dbtower.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 인덱스 사용 관측 기간 (운영 병목 아크 B3, I3) — DBTower가 이 인스턴스의 인덱스 사용을 얼마나
 * 오래 지켜봤는지. index_usage_snapshot의 가장 이른 기록부터 지금까지의 일수다.
 *
 * <p>왜 공개 API인가: FinOps의 미사용 인덱스 신호가 "관측 3일"과 "관측 90일"을 구분해 표기하려면
 * 이 값이 필요한데, 스냅샷 테이블은 insight 소유라 finops가 직접 못 읽는다(Modulith 경계).
 * 그래서 insight가 이 조회를 공개 서비스로 노출하고 finops가 참조한다(finops -> insight 단방향).
 *
 * <p>정직: 이 값은 "DBTower가 관측한 기간"이지 "대상 카운터가 리셋 없이 누적된 기간"이 아니다.
 * 서버가 그 사이 재기동했다면 scan_count는 리셋됐을 수 있다 — 그 한계는 소비자(FinOps)가 함께 안내한다.
 */
@Service
public class IndexUsageHistoryService {

    private final JdbcTemplate jdbc;

    public IndexUsageHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 관측 일수 — 가장 이른 스냅샷부터 지금까지. 스냅샷이 아직 없으면(첫 수집 전) 빈 값.
     * 하루 미만이면 0을 담아 "관측 시작"을 구분한다.
     */
    public Optional<Long> observationDays(long instanceId) {
        Timestamp earliest = jdbc.query(
                "SELECT min(captured_at) AS e FROM index_usage_snapshot WHERE instance_id = ?",
                rs -> rs.next() ? rs.getTimestamp("e") : null, instanceId);
        if (earliest == null) {
            return Optional.empty();
        }
        long days = Duration.between(earliest.toLocalDateTime(), LocalDateTime.now()).toDays();
        return Optional.of(Math.max(0, days));
    }
}
