package io.dbtower.score;

/**
 * 헬스 스코어 공개 조회 (운영 병목 아크 B5). ScoreService(score.internal)가 구현한다 —
 * 월간 리포트 등 다른 모듈이 score 내부를 참조하지 않고 이 인터페이스로만 건강을 읽는다
 * (Modulith 경계).
 */
public interface ScoreQuery {

    /** 인스턴스 하나의 헬스 스코어 요약. 스코프 밖이면 findById가 404. */
    HealthScoreView scoreFor(Long instanceId);
}
