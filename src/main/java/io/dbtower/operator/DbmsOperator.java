package io.dbtower.operator;

import io.dbtower.registry.HealthStatus;

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

    /** 추상 정책을 기종별 백업 방식으로 실행 (확장1) — mysqldump / pg_dump / BACKUP DATABASE */
    BackupResult backup(BackupPolicy policy);

    /**
     * 백업 산출물이 실제로 복원 가능한지 검증한다 (A7 — "0 errors"의 자동화).
     * location은 backup()이 돌려준 산출물 위치. 기종·모드에 따라 확인 가능한 수준이 달라
     * 결과는 VERIFIED/FAILED/UNSUPPORTED 3-값이다(RestoreVerification 참고).
     * 복원은 반드시 임시/격리 대상에만 한다 — 원본 스키마·데이터는 절대 건드리지 않는다.
     */
    RestoreVerification verifyRestore(String location);

    /** 복제 토폴로지 상태 (확장2) */
    ReplicationState replicationState();

    /**
     * 상위 대기 이벤트 (B1) — "그 시간에 무엇을 기다렸나"(CPU/IO/Lock).
     * 기종별 의미 차이(누적 vs 순간 스냅샷 vs 큐 지표)는 WaitEvent 주석 참고.
     */
    List<WaitEvent> waitEvents(int limit);
}
