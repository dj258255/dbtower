-- 인덱스 사용 통계 주기 영속 (운영 병목 아크 B3) — lakehouse "미사용 인덱스 장기 판정"의 공급원.
--
-- 왜: "이 인덱스 지워도 되나"는 재시작-누적 카운터의 순간값으론 답할 수 없다("지난주 재기동
-- 이후 0회"와 "분기 내내 0회"가 구분 안 됨). 분기 창 판정은 lakehouse 몫이고, DBTower는
-- 원료(주기 스냅샷)를 낳는다 — wait_event_snapshot·size_snapshot과 같은 패턴.
--
-- 형태: operator.indexUsage() 실형 그대로(테이블·인덱스·scan_count·size_bytes·unique). scan_count는
-- 통계 리셋 이후 누적이라, lakehouse가 first-vs-last 델타·순리셋 클램프로 실사용을 복원한다(그쪽 16단계).
-- 여기 7일만 살고(관제 DB 무한 성장 방지) lakehouse가 D+1로 내려 장기 보관한다.
CREATE TABLE index_usage_snapshot (
    id          BIGSERIAL   PRIMARY KEY,
    instance_id BIGINT      NOT NULL,
    captured_at TIMESTAMP   NOT NULL,
    table_name  VARCHAR(255) NOT NULL,
    index_name  VARCHAR(255) NOT NULL,
    scan_count  BIGINT,                              -- 통계 리셋 이후 누적(미확보 null)
    size_bytes  BIGINT,                              -- 인덱스 크기(미확보 null)
    is_unique   BOOLEAN     NOT NULL,
    CONSTRAINT fk_index_usage_snapshot_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);

-- 조회·추출 패턴: 인스턴스별 시간창(lakehouse offload 인덱스 선두 원칙과 동일).
CREATE INDEX idx_index_usage_snapshot_instance_time
    ON index_usage_snapshot (instance_id, captured_at);
