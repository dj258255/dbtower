-- 변경 티켓의 사람 개입 기록 (워크벤치 3단계 후속).
--
-- 왜: 실행 흐름에 빠진 두 출구가 있었다. (1) 승인됐지만 실행하지 않을 티켓을 닫을 방법이 없어 APPROVED로 영원히 남았다.
-- (2) 커밋 호출이 실패해 반영 여부를 모르는 티켓은 재실행을 막으려고 EXECUTING/ROLLING_BACK에 묶어 두는데, 사람이 대상 DB를
-- 확인한 뒤 풀어 줄 경로가 없었다. 둘 다 "사람이 상태를 바꾼 마지막 개입"이라 누가·언제·무엇을 확인했는지 같은 열에 남긴다.
--
-- 상태 추가: CANCELLED (PENDING·APPROVED에서만. 실행권이 잡힌 티켓은 취소가 아니라 확인 뒤 정리로 푼다)
ALTER TABLE review_request ADD COLUMN intervened_by VARCHAR(255);
ALTER TABLE review_request ADD COLUMN intervened_at TIMESTAMP;
ALTER TABLE review_request ADD COLUMN intervention_note TEXT;
