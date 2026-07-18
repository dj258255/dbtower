-- 오브젝트 크기 주기 영속 (Phase 5 forward, size 공급) — lakehouse 용량 예측(13단계)의 원료.
--
-- 왜: tableStats()는 "지금 크기"만 보여준다. "이 DB 몇 달 뒤 꽉 차나"(장기 D-day)와
-- "다음 분기 증설 예산"은 크기의 **시계열**이 있어야 증가율(GB/일)이 나온다 — 단기
-- 디스크 ETA(78절, Prometheus 라이브)와 지평이 다른 층이다(단기=라이브 카나리아,
-- 장기=lakehouse 추세. 경계는 lakehouse ROADMAP 13단계).
--
-- volume_*·max_bytes: 임계(분모)의 원천 ② — 기종이 스스로 아는 볼륨 총량(MSSQL
-- dm_os_volume_stats, Oracle maxbytes). 현 수집기는 아직 채우지 않는다(NULL) —
-- 계약이 nullable로 설계된 이유(lakehouse CONTRACT 13단계 C1). 지어내지 않는다.
CREATE TABLE size_snapshot (
    id                     BIGSERIAL PRIMARY KEY,
    instance_id            BIGINT       NOT NULL,
    captured_at            TIMESTAMP    NOT NULL,
    object_type            VARCHAR(10)  NOT NULL DEFAULT 'table',
    object_name            VARCHAR(255) NOT NULL,
    row_estimate           BIGINT       NOT NULL,
    data_bytes             BIGINT       NOT NULL,
    index_bytes            BIGINT       NOT NULL,
    volume_total_bytes     BIGINT,
    volume_available_bytes BIGINT,
    max_bytes              BIGINT,
    CONSTRAINT fk_size_snapshot_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);

CREATE INDEX idx_size_snapshot_instance_time ON size_snapshot (instance_id, captured_at);
