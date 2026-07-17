-- Discord 알림 메시지 id ↔ 대상 인스턴스 매핑 영속화.
-- 인메모리 인덱스는 재시작하면 비어서, 재시작 전에 온 알림에는 반응해도 진단이 안 됐다
-- ("대상을 알 수 없다" — embed 파싱 폴백은 Message Content 특권 인텐트가 없어 사실상 막힘).
-- 메타 DB에 두면 재시작·다중 노드에서도 옛 알림 반응이 동작한다(공유 세션과 같은 이유).
CREATE TABLE alert_message_index (
    message_id  VARCHAR(32)  PRIMARY KEY,
    instance_id BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- 보존 정리(오래된 매핑 삭제)용
CREATE INDEX idx_alert_message_index_created ON alert_message_index (created_at);
