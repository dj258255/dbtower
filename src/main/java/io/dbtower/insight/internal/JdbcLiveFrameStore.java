package io.dbtower.insight.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;

/**
 * 메타 DB의 {@code live_frame}(UNLOGGED)로 프레임을 주고받는다(V41). 대상마다 한 행을 덮어쓴다.
 * seq는 upsert 한 문장 안에서 DB가 올린다 — 조회권이 노드를 옮겨도 번호가 이어지고, 두 노드가 같은 번호를 매길 수 없다.
 */
@Component
class JdbcLiveFrameStore implements LiveFrameStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String PUBLISH = """
            INSERT INTO live_frame (instance_id, seq, frame, produced_at, producer)
            VALUES (?, 1, ?, now(), ?)
            ON CONFLICT (instance_id) DO UPDATE
               SET seq = live_frame.seq + 1, frame = EXCLUDED.frame,
                   produced_at = EXCLUDED.produced_at, producer = EXCLUDED.producer
            RETURNING seq
            """;

    private static final String LATEST = """
            SELECT seq, frame, (EXTRACT(EPOCH FROM (now() - produced_at)) * 1000)::bigint AS age_ms
              FROM live_frame WHERE instance_id = ?
            """;

    private final JdbcTemplate jdbc;
    /** 어느 노드가 조회했는지 — 계측과 장애 분석에서 "누가 대상에 물었나"에 답하려고 남긴다 */
    private final String producer = ManagementFactory.getRuntimeMXBean().getName();

    JdbcLiveFrameStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long publish(LiveFrame frame) {
        Long seq = jdbc.queryForObject(PUBLISH, Long.class, frame.instanceId(), write(frame), producer);
        return seq == null ? 0 : seq;
    }

    @Override
    public Optional<Stored> latest(long instanceId) {
        List<Stored> rows = jdbc.query(LATEST, (rs, i) ->
                new Stored(read(rs.getString("frame")).withSeq(rs.getLong("seq")), rs.getLong("age_ms")), instanceId);
        return rows.stream().findFirst();
    }

    private static String write(LiveFrame frame) {
        try {
            return MAPPER.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("실시간 프레임 직렬화 실패: " + e.getMessage(), e);
        }
    }

    private static LiveFrame read(String json) {
        try {
            return MAPPER.readValue(json, LiveFrame.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("실시간 프레임 역직렬화 실패: " + e.getMessage(), e);
        }
    }
}
