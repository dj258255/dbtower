package io.dbtower.insight.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 장기 베이스라인(baseline_longterm) 읽기 전용 DAO (Phase 5 reverse ETL, D8).
 *
 * 이 테이블의 행은 lakehouse writeback이 소유한다 — DBTower는 읽기만 한다(방향 역전:
 * 평소엔 lakehouse가 우리 것을 읽어 가지만, 이 테이블만은 그쪽이 쓰고 우리가 소비한다).
 * 스키마 계약은 lakehouse docs/CONTRACT.md §1-2가 단일 진실.
 *
 * 실패 안전: 조회 실패(테이블 부재·권한 등)는 빈 맵으로 강등한다 — 장기 베이스라인은
 * 보강 신호지 필수 입력이 아니므로, 이 경로의 어떤 문제도 이상 감지 자체를 죽여선 안 된다
 * (미존재/빈 테이블이면 현행 14일 창 그대로 = 회귀 0이 계약).
 */
@Component
public class BaselineLongtermDao {

    private static final Logger log = LoggerFactory.getLogger(BaselineLongtermDao.class);

    private final JdbcTemplate jdbc;
    /** 조회 실패를 최초 1회만 경고한다(폴러가 주기 호출 — 로그 폭주 방지). */
    private volatile boolean failureLogged;

    public BaselineLongtermDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 (dow, hour) 버킷의 쿼리별 장기 통계 — 관측 수·시간당 호출량 평균·표준편차. */
    public record LongtermStat(long observations, double meanDeltaCalls, double stddevDeltaCalls) {
    }

    /**
     * (인스턴스, 요일, 시간대) 버킷의 쿼리별 장기 통계를 통째로 읽는다.
     * dow는 lakehouse 규약(일=0..토=6, UTC) — 호출자가 java.time DayOfWeek에서 변환해 넘긴다.
     */
    public Map<String, LongtermStat> findBucket(Long instanceId, int dow, int hour) {
        try {
            Map<String, LongtermStat> out = new HashMap<>();
            jdbc.query(
                    "SELECT query_id, observations, mean_delta_calls, stddev_delta_calls "
                            + "FROM baseline_longterm "
                            + "WHERE instance_id = ? AND dow = ? AND hour = ?",
                    rs -> {
                        long n = rs.getLong("observations");
                        double mean = rs.getDouble("mean_delta_calls");
                        double stddev = rs.getDouble("stddev_delta_calls");
                        if (rs.wasNull()) {
                            stddev = 0; // 관측 1개 버킷 — 분산 정보 없음(계약)
                        }
                        if (n > 0) {
                            out.put(rs.getString("query_id"), new LongtermStat(n, mean, stddev));
                        }
                    },
                    instanceId, dow, hour);
            return out;
        } catch (DataAccessException e) {
            if (!failureLogged) {
                failureLogged = true;
                log.warn("baseline_longterm 조회 실패 — 장기 병합 없이 현행 베이스라인으로 계속(이후 동일 경고 생략): {}",
                        e.getMessage());
            }
            return Map.of();
        }
    }
}
