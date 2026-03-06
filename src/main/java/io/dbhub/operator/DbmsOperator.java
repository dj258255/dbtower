package io.dbhub.operator;

import java.util.List;

/**
 * 이기종 DBMS 운영 작업의 공통 경계.
 *
 * 플랫폼의 모든 기능(헬스체크·통계 수집·슬로우 쿼리·실행계획·백업·복제 상태)은
 * 이 인터페이스에만 의존하고, 기종별 차이(통계 소스·구문·백업 도구)는 구현체가 흡수한다.
 * 새 DBMS 지원 = 이 인터페이스의 구현체 하나 추가.
 */
public interface DbmsOperator {

    /** 연결 확인 + 버전 + 응답시간 */
    HealthStatus health();

    /** 정규화된 쿼리별 누적 통계 상위 N개 (시점 비교의 원천 데이터) */
    List<QueryStat> queryStats(int limit);

    /** 느린 쿼리 상위 N개 */
    List<SlowQuery> slowQueries(int limit);

    /** SELECT 쿼리의 실행계획(JSON/XML 문자열) */
    String explain(String sql);

    /** 테이블별 행수·데이터/인덱스 크기 — 용량 추이와 튜닝 판단의 기초 자료 */
    List<TableStat> tableStats(int limit);

    /** 추상 정책을 기종별 백업 구문으로 실행 (확장1) */
    void backup(BackupPolicy policy);

    /** 복제 토폴로지 상태 (확장2) */
    ReplicationState replicationState();
}
