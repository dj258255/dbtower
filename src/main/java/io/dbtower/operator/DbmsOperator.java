package io.dbtower.operator;

import io.dbtower.operator.model.WaitEvent;
import io.dbtower.operator.model.TableStat;
import io.dbtower.operator.model.TableBloat;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.model.ReplicationSlot;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.PartitionInfo;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.IndexUsage;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.DeadlockEvent;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.registry.HealthStatus;

import java.util.List;
import java.util.Optional;

/**
 * 이기종 DBMS 운영 작업의 공통 경계.
 *
 * 플랫폼의 모든 기능(헬스체크·통계 수집·슬로우 쿼리·실행계획·백업·복제 상태)은
 * 이 인터페이스에만 의존하고, 기종별 차이(통계 소스·구문·백업 도구)는 구현체가 흡수한다.
 * 새 DBMS 지원 = 이 인터페이스의 구현체 하나 추가.
 */
public interface DbmsOperator {

    /**
     * 심층 진단(explainAnalyze)이 실제로 쿼리를 실행할 때 거는 안전 타임아웃(ms).
     * 진단 도구가 부하 유발자가 되지 않도록 전 기종 공통 상한 — 각 구현이 기종별 타임아웃 수단으로 적용한다.
     */
    long DEEP_DIAGNOSIS_TIMEOUT_MS = 10_000L;

    /** 연결 확인 + 버전 + 응답시간 */
    HealthStatus health();

    /** 정규화된 쿼리별 누적 통계 상위 N개 (시점 비교의 원천 데이터) */
    List<QueryStat> queryStats(int limit);

    /** 느린 쿼리 상위 N개 */
    List<SlowQuery> slowQueries(int limit);

    /** SELECT 쿼리의 실행계획(JSON/XML 문자열) — 옵티마이저의 <b>추정</b> 계획(쿼리를 실행하지 않음) */
    String explain(String sql);

    /**
     * 파라미터 플레이스홀더가 남은 <b>정규화 텍스트</b>의 실행계획 — 플랜 변경 감지용.
     * 통계 소스의 쿼리 텍스트는 리터럴이 지워진 형태($1·?)라 일반 explain이 실패한다.
     * PostgreSQL 16+는 EXPLAIN (GENERIC_PLAN)으로 플레이스홀더 채로 계획을 뽑을 수 있어 오버라이드하고,
     * 기본 구현은 explain() 위임 — 플레이스홀더 없는 텍스트에서만 성립한다(호출부가 걸러낸다).
     */
    default String explainNormalized(String sql) {
        return explain(sql);
    }

    /**
     * 플랜 변경 감지(plan flip)용 — 정규화 쿼리($1·? 플레이스홀더)의 실행계획 <b>형태(shape)</b>.
     * "쿼리도 데이터도 그대로인데 갑자기 느려짐 = 옵티마이저가 플랜을 갈아탐"을 잡는다.
     *
     * 통계 소스의 쿼리 텍스트는 리터럴이 지워진 형태라 그대로 explain이 안 되는 게 벽인데, 기종마다
     * 넘는 길이 전부 다르다(전부 읽기 전용):
     * - PostgreSQL: EXPLAIN (GENERIC_PLAN) — 플레이스홀더 채로 제네릭 플랜
     * - MySQL: performance_schema digest의 QUERY_SAMPLE_TEXT(리터럴 샘플)를 EXPLAIN FORMAT=JSON
     * - SQL Server: Query Store(sys.query_store_plan)의 계획 이력 — 활성일 때만("있으면 쓴다")
     * - Oracle: v$sqlstats의 (sql_id, plan_hash_value) — 플랜 해시가 곧 형태 식별자
     * - MongoDB: system.profile 샘플 명령을 explain(queryPlanner)으로 재실행
     *
     * 반환은 {@link PlanShapes}로 정규화한 shape 문자열(구조는 남기고 수치는 버림).
     * 얻을 수 없으면 {@code Optional.empty()} — 지어내지 않는다(플레이스홀더를 임의 값으로 채우면
     * 타입에 따라 다른 플랜이 나와 "가짜 변경"이 된다). 기본은 미지원.
     *
     * @param queryId   통계 소스의 쿼리 식별자(digest/query_hash/sql_id/queryHash)
     * @param queryText 정규화 쿼리 텍스트(기종에 따라 SQL 또는 명령 JSON)
     */
    default Optional<String> planShapeForDigest(String queryId, String queryText) {
        return Optional.empty();
    }

    /**
     * 심층 원인 진단용 <b>실제 실행 계획</b> (D9) — explain()이 추정만 보는 것과 달리,
     * 쿼리를 진짜 실행해 추정 행수 vs 실제 행수의 괴리(카디널리티 오추정)를 드러낸다.
     * "무엇이 느린가"를 넘어 "왜 인덱스를 못 타나"를 짚는 근본원인 진단의 원천 데이터다.
     *
     * 기종별 획득 방식(docs/ai-analysis-rules.md "심층 원인 규칙 (D9)" 절이 스펙):
     * MySQL=EXPLAIN ANALYZE FORMAT=JSON(actual_* 필드), PostgreSQL=EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON),
     * Oracle=/*+ gather_plan_statistics *&#47; 후 DBMS_XPLAN.DISPLAY_CURSOR('ALLSTATS LAST')(같은 커넥션),
     * SQL Server=SET STATISTICS XML ON(플랜이 별도 결과셋), MongoDB=explain verbosity executionStats.
     *
     * <b>실제로 실행하므로 위험하다</b> — 반드시 SELECT 전용(requireSelect)이고 타임아웃을 건다
     * (MySQL MAX_EXECUTION_TIME 힌트·PG SET LOCAL statement_timeout·Mongo maxTimeMS·나머지 setQueryTimeout).
     * 진단 도구가 부하 유발자가 되면 안 된다. 권한·버전 미달로 실제 계획을 못 얻으면 OperatorException으로
     * 실제 원인을 그대로 올린다(추정 계획으로 몰래 대체하지 않는다 — 위장 금지). 반환은 기종 원문(JSON/텍스트/XML).
     */
    String explainAnalyze(String sql);

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

    /**
     * 현재 활성 세션과 블로킹 관계 (B2) — "지금 누가 누구를 막고 있나". 읽기 전용.
     * 기종별 pid 의미와 블로킹 소스는 SessionInfo 주석 참고.
     */
    List<SessionInfo> activeSessions(int limit);

    /**
     * 인덱스 어드바이저 (B3) — 후보 인덱스를 실제로 만들지 않고 가상으로 시뮬레이션해
     * "이 인덱스를 만들면 플랜 비용이 이렇게 바뀐다"를 돌려준다. 읽기 전용(SELECT만).
     *
     * columns는 "table(col1,col2)" 형태의 후보 인덱스 컬럼. PostgreSQL은 HypoPG로 진짜 가상
     * 인덱스를 만들어 EXPLAIN 비용을 비교한다. 타 기종은 실제 인덱스 없이 시뮬레이션할 표준 수단이
     * 없어 UNSUPPORTED다(통과 위장 금지 — IndexAdvice 주석 참고).
     */
    IndexAdvice adviseIndex(String sql, String columns);

    /**
     * 세션 하나를 안전하게 종료한다 (B2) — 반드시 명시적 pid 하나만. 대량/와일드카드 kill은 없다.
     * force=false는 실행 중 문장만 취소(cancel), force=true는 세션 자체를 강제 종료(terminate).
     * SQL Server처럼 취소/강제 구분이 없는 기종은 force를 무시한다(구현 주석에 명시).
     * 자기 수집 커넥션은 종료 대상에서 제외한다. 결과 문자열은 어떤 방식으로 무엇을 했는지 기술한다.
     */
    String killSession(long pid, boolean force);

    /**
     * 스키마 구조 스냅샷 (B7) — "왜 저 장비만 다르지"를 컬럼·인덱스 수준에서 추적하는 원천.
     * 읽기 전용(information_schema/카탈로그 조회만). 시스템 스키마는 제외하고 대상 dbName/스키마의
     * 사용자 테이블만, 대량 스키마 방어를 위해 상위 N개(하드 상한)까지만 담는다.
     * 완벽한 DDL 재현이 아니라 diff에 필요한 구조 요약임은 SchemaSnapshot 주석 참고.
     */
    SchemaSnapshot describeSchema();

    /**
     * 인스턴스 설정 파라미터 전량 (B6 파라미터 드리프트) — "왜 저 장비만 느리지"의 단골 원인인
     * 설정값 차이(max_connections·work_mem 등)를 같은 역할의 두 장비 사이에서 추적하는 원천.
     * 읽기 전용(설정 카탈로그 조회만 — 설정을 바꾸지 않는다).
     *
     * 수백 개를 전량 반환하고, 차이만 보여주는 일은 ParameterDiffService가 한다. 민감 파라미터
     * (비밀번호·키류)는 값을 마스킹한다(ParameterSupport). 기종별 소스는 각 구현 주석 참고.
     */
    List<DbParameter> parameters();

    /**
     * 쿼리별 레이턴시 백분위 p95/p99 (D4a) — "평균은 괜찮은데 꼬리가 아프다"를 드러내는 사용자 경험 지표.
     * 같은 지표라도 기종마다 원자료 가용성이 달라, 값과 함께 그 값의 출처(source)를 반드시 구분한다:
     * MySQL은 QUANTILE 컬럼(NATIVE), Mongo는 profile 원샘플 직접 계산(COMPUTED), PostgreSQL은
     * 평균+표준편차 근사(ESTIMATED), SQL Server/Oracle은 원자료 없음(UNSUPPORTED). 절대 섞지 않는다 —
     * 추정을 실측인 척, 미지원을 지원인 척하지 않는다. 기종별 소스·한계는 LatencyPercentile record 주석 참고.
     */
    List<LatencyPercentile> latencyPercentiles(int limit);

    /**
     * 파티션 목록 조회 (D5 파티션 조회) — "이 인스턴스의 어떤 테이블이 어떻게 쪼개져 있고, 각 조각이
     * 얼마나 큰가"를 이기종 통합으로 본다. <b>조회만</b> 한다 — 파티션 생성·삭제·자동 관리는 범위 밖이고
     * 대상 DB를 바꾸지 않는다(읽기 전용 카탈로그 조회). KDMS가 MCP로 제공한 6기능 중 마지막 조각.
     *
     * 기종별 소스(MySQL=information_schema.PARTITIONS, PostgreSQL=선언적 파티셔닝 카탈로그,
     * Oracle=user_tab_partitions, SQL Server=sys.partitions+파티션 스킴/함수)와 필드 의미는
     * PartitionInfo record 주석 참고. 파티션 없는 테이블/DB는 빈 결과(에러 아님), MongoDB는 파티션
     * 개념이 없어 UNSUPPORTED로 정직하게 표기한다(샤딩은 다른 축이라 여기 섞지 않는다).
     */
    List<PartitionInfo> partitions(int limit);

    /**
     * 인덱스 사용 통계 (D6 FinOps) — "이 인덱스가 실제로 쓰이나"를 기종별 사용 카운터로 읽는다.
     * 미사용 인덱스는 쓰기 비용·저장공간만 잡아먹는 대표적 낭비 후보인데, 구조만 보는 describeSchema로는
     * "안 쓰였는지"를 알 수 없어 이 사용 통계가 필요하다. 읽기 전용(카탈로그·성능 뷰 조회만).
     *
     * scanCount는 통계 리셋 이후 <b>누적</b> 사용 횟수라 0회가 곧 미사용인지는 서버 가동 기간을 함께 봐야
     * 한다(IndexUsage 주석 참고). 사용 통계를 신뢰성 있게 얻을 수 없는 기종(Oracle: 인덱스 사용 추적이
     * 기본·권한 보장 아님)은 UNSUPPORTED 안내 행으로 정직하게 표기한다(지원 위장 금지). limit로 상한.
     */
    List<IndexUsage> indexUsage(int limit);

    /**
     * 복제 슬롯 상태 (C-1, PostgreSQL 전용) — 비활성 슬롯이 WAL을 무한 보존해 디스크를 고갈시키는
     * 대표 장애의 사각을 본다. pg_stat_replication은 "연결된 복제"만 보여줘 이걸 못 잡는다. 읽기 전용.
     * 슬롯 개념이 없거나 조회 불가한 기종은 빈 목록(기본).
     */
    default List<ReplicationSlot> replicationSlots() {
        return List.of();
    }

    /**
     * 테이블 블로트/VACUUM 신호 (C-2, PostgreSQL 전용) — autovacuum이 죽은 튜플을 못 따라가는 것을
     * pg_stat_user_tables로 감지한다(추정치 기반 — 실측 아님). 읽기 전용. 미지원 기종은 빈 목록(기본).
     */
    default List<TableBloat> tableBloat(int limit) {
        return List.of();
    }

    /**
     * 최근 데드락 기록 (3차 아크 D-축) — DB가 이미 남긴 흔적에서 설정 변경 0으로 읽는다.
     * SQL Server는 system_health XE(여러 건), MySQL은 SHOW ENGINE INNODB STATUS(최근 1건).
     * PostgreSQL은 개별 사건이 없어(누적 카운터뿐) 여기 아니라 OpsAlert 카운터 델타로 다룬다.
     * 롤링 저장이라 "최근"만 — 과거 전수는 보장하지 않는다. 읽기 전용. 미지원/무발생은 빈 목록(기본).
     */
    default List<DeadlockEvent> recentDeadlocks(int limit) {
        return List.of();
    }

    /**
     * 누적 데드락 카운터 (3차 아크 D-3, PostgreSQL 전용) — PG는 개별 데드락 리포트를 안 남기고
     * pg_stat_database.deadlocks 누적 카운터만 준다. 값 자체가 아니라 폴 사이 <b>델타</b>가 신호다
     * (OpsAlert가 이전 값과 비교해 "새 데드락 N건"으로 알린다). 카운터가 없는 기종은 empty(기본).
     */
    default Optional<Long> deadlockCount() {
        return Optional.empty();
    }
}
