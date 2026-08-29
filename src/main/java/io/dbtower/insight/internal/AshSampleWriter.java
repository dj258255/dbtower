package io.dbtower.insight.internal;

import io.dbtower.insight.AshSample;
import io.dbtower.insight.AshSampleTick;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * ASH 샘플 저장 전용 컴포넌트 — {@link SnapshotWriter}와 같은 패턴(JDBC batchUpdate).
 *
 * <p>샘플은 불변 로그라 영속성 컨텍스트가 필요 없다. PG에서 진짜 배치가 되려면 URL에
 * reWriteBatchedInserts=true가 필요한 것도 같다.
 *
 * <p>다른 점 하나: {@code ON CONFLICT DO NOTHING}을 건다. 유니크 키가
 * (instance_id, sampled_at, pid)이므로 같은 틱을 두 번 쓰려 해도 행이 늘지 않는다.
 * 재시도 경로가 생겨도 중복률이 0으로 유지되는 근거가 여기다 — 그리고 중복률을 "재려면"
 * 이 방어를 끄고 재야 하므로, 계측 시나리오에서는 별도 스테이징으로 잰다.
 */
@Component
public class AshSampleWriter {

    private static final String INSERT_SAMPLE = """
            INSERT INTO ash_sample
                (instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
                 pid, username, state, wait_event, wait_category,
                 blocked_by_pid, query_fingerprint, elapsed_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instance_id, sampled_at, pid) DO NOTHING
            """;

    private static final String INSERT_TICK = """
            INSERT INTO ash_sample_tick
                (instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
                 observed_sessions, retained_sessions, dropped_sessions,
                 collect_ms, status, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instance_id, sampled_at) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public AshSampleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveSamples(List<AshSample> rows) {
        if (rows.isEmpty()) {
            return; // 틱 자체는 saveTick이 따로 남긴다 — 0건과 미수행을 구분하는 지점
        }
        jdbc.batchUpdate(INSERT_SAMPLE, rows, rows.size(), (ps, s) -> {
            ps.setLong(1, s.instanceId());
            ps.setTimestamp(2, Timestamp.valueOf(s.sampledAt()));
            ps.setTimestamp(3, Timestamp.valueOf(s.ingestedAt()));
            ps.setString(4, s.samplerRunId().toString());
            ps.setLong(5, s.sampleSeq());
            ps.setLong(6, s.pid());
            ps.setString(7, s.username());
            ps.setString(8, s.state());
            ps.setString(9, s.waitEvent());
            ps.setString(10, s.waitCategory());
            if (s.blockedByPid() == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, s.blockedByPid());
            }
            ps.setString(12, s.queryFingerprint());
            ps.setDouble(13, s.elapsedMs());
        });
    }

    /** 틱 메타는 성공·스킵·실패 모두 남긴다. 남기지 않으면 "안 돈 것"과 "0건"이 같아진다. */
    public void saveTick(AshSampleTick t) {
        jdbc.update(INSERT_TICK,
                t.instanceId(), Timestamp.valueOf(t.sampledAt()), Timestamp.valueOf(t.ingestedAt()),
                t.samplerRunId().toString(), t.sampleSeq(),
                t.observedSessions(), t.retainedSessions(), t.droppedSessions(),
                t.collectMs(), t.status(), t.errorMessage());
    }
}
