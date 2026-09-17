-- 인스턴스별 스냅샷 수집 결과 (#72)
--
-- 인스턴스 카드의 "수집중"은 수집 설정과 헬스 판정만 봤다. 대상은 살아 있는데 저장이 매번 실패하던 동안(#70)에도 초록이었다.
-- 수집기가 인스턴스마다 마지막 성공·실패와 연속 실패 수를 남기고, 화면은 이 표를 읽는다.
-- 수집은 ShedLock으로 한 노드만 돌지만 화면 요청은 어느 노드로든 가므로 노드 메모리가 아니라 메타 DB에 둔다.
-- failure_stage: TARGET(대상 통계 조회 실패) / STORE(플랫폼 저장 실패). 원문 오류는 로그에만 남긴다.
CREATE TABLE collection_status (
    instance_id          BIGINT      PRIMARY KEY,
    last_success_at      TIMESTAMP,
    last_failure_at      TIMESTAMP,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    failure_stage        VARCHAR(20)
);
