-- 반응 처리 이력 영속화 — 처리 여부가 인메모리라 재시작하면 이미 답글 단 알림을
-- 보충 스캔이 다시 진단했다(실측: 재시작 후 같은 메시지에 중복 진단 답글).
-- IF NOT EXISTS: 개발 중 수동 선적용 환경과의 충돌 방지(멱등).
ALTER TABLE alert_message_index ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP NULL;
