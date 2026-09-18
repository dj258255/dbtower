# 하드닝 로드맵 — 4축 감사 → 검증된 수정 계획

> **전 항목 v1.1.0에서 완료** (VERIFICATION 57~62절, CHANGELOG 1.1.0). 이 문서는 당시의
> 감사·결정 기록으로 보존한다. 단 A-3(암호화 fail-closed)은 prod 프로필만 커버 —
> docker 프로필 잔여는 ROADMAP "프로덕션 로드맵" P0-2가 승계한다.

> 2026-07-07. 동시성·자원누수 / 기종별 정확성 / 보안 / HA·수명주기 4개 축 병렬 감사 결과를,
> 코드 재검증 + 웹서칭(OWASP·CWE·벤더 문서)으로 확인해 FIX/SKIP 결정으로 정리한다.
> 각 항목: [결정] 위치 — 근거 — (근거 URL) — 수정 요지. 서브에이전트가 파일 소유권으로 분할 작업한다.

## 파일 소유권(충돌 방지 — 워크스트림당 배타)

- **WS-A 보안**: MsSqlOperator.java, PlanShapes.java, AbstractJdbcOperator.java, SecretCipher.java,
  EncryptedStringConverter.java, WebhookNotifier.java(멘션 방어만), GlobalExceptionHandler.java + 해당 테스트
- **WS-B 수명주기·동시성**: SnapshotScheduler.java, OpsAlertDetector.java, HistogramSnapshotStore.java,
  OperatorInstanceOperations.java, RegistryService.java, ConnectionPools.java, plan 보존 잡,
  신규 마이그레이션 V10 + 해당 테스트
- **WS-C 기종정확성·타임존**: MySqlOperator.java, PostgresOperator.java, OracleOperator.java,
  application.yml, DbtowerApplication.java(타임존) + 해당 테스트

주의: WebhookNotifier는 WS-A(멘션 방어)와 WS-B(비동기 전송) 둘 다 손대므로 **WS-B가 소유**하고,
WS-A는 멘션 방어를 WS-B에 위임(아래 B-9로 통합). SnapshotScheduler·OpsAlertDetector는 WS-B 전용.

---

## WS-A 보안

### A-1 [FIX·HIGH] XXE 방어 미완 — XML 파서 3곳
- 위치: MsSqlOperator.java:787-788(parseDeadlockXml), :847-848(parseRingBufferDeadlocks),
  PlanShapes.java:200-201(fromMssqlXml). 현재 `load-external-dtd=false` + `setExpandEntityReferences(false)`만.
- 근거: OWASP가 명시한 불충분 조합. 같은 저장소 DeepAnalyzer.java:257-258은 이미 올바르게 방어
  (`FEATURE_SECURE_PROCESSING`=true + `disallow-doctype-decl`=true). 즉 설계가 아닌 누락.
  https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html · CWE-611
- 수정: 세 곳 모두 DeepAnalyzer와 동일하게 `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)` +
  `setFeature(".../disallow-doctype-decl",true)` 추가. 단위 테스트: DOCTYPE+외부엔티티 페이로드가 파싱에서
  거부/무시되는지(파일 fetch 안 함).

### A-2 [FIX·MED] requireSelect 스택 쿼리 우회
- 위치: AbstractJdbcOperator.java:107-111(startsWith("select")만), MsSqlOperator.java:667(plain Statement.execute).
- 근거: `SELECT 1; DROP TABLE x`가 게이트 통과 후 배치 실행. ADMIN-gated라 MED이나 읽기전용 불변식 위반. CWE-89.
- 수정: requireSelect에 세미콜론 기반 다중문 거부(문자열 리터럴 밖 `;`) 추가. MSSQL explainAnalyze도 방어 확인.
  단위: `SELECT 1; DROP ...` 거부, 정상 SELECT 통과.

### A-3 [FIX·MED] 암호화 fail-open — 키 미설정 시 조용한 평문 저장
- 위치: EncryptedStringConverter.java:45-46(키 없으면 평문 반환), SecretCipher.java:47(WARN만).
- 근거: 운영 프로필에서 키 누락 시 대상 DB 비밀번호 전량 평문. API 토큰·유저는 fail-closed인데 여기만 fail-open. CWE-312.
- 수정: 운영 프로필(prod) 활성 시 키 없으면 기동 실패(또는 readiness 실패)로 격상. dev는 WARN 유지(하위호환).
  구현: SecretCipher가 활성 프로필 인지 → prod면 키 필수. 테스트: prod+키없음 → 예외.

### A-5 [FIX·HIGH] MSSQL 데드락이 Azure SQL Database에서 조용히 "없음"
- 위치: MsSqlOperator.java:726·736 (system_health `.xel` 파일·세션 하드코딩).
- 근거: system_health 세션·로컬 .xel이 Azure SQL DB엔 없음 → 실제 데드락이 빈 목록(오탐). jdbcUrl 주석은 Azure 지원 표방.
  https://learn.microsoft.com/en-us/sql/relational-databases/system-functions/sys-fn-xe-file-target-read-file-transact-sql
- 수정: `SERVERPROPERTY('EngineEdition')`=5(Azure SQL DB)면 recentDeadlocks가 UNSUPPORTED 성격의 명시적
  빈+사유(로그/주석)로 정직 처리(오탐 대신 "이 에디션 미지원"). MI/온프렘(system_health 존재)은 기존 경로.

### A-4 [FIX·LOW] 대상 DB 에러 텍스트 API 노출
- 위치: GlobalExceptionHandler.java:22-26 (OperatorException.getMessage 그대로 502 body).
- 근거: 대상 스키마·객체명 누출(자격증명은 안 샘). CWE-209.
- 수정: 상세는 서버 로그, 응답은 일반 메시지 + 에러 ID.

### [SKIP] A-actuator permitAll (SecurityConfig)
- 근거: /actuator/prometheus·health 무인증은 배포 네트워크 제한 전제(주석 명시). 데모/포트폴리오 범위에선 수용.
  로드맵에 "배포 시 네트워크 제한 필수"로만 문서화.

---

## WS-B 수명주기·동시성

### B-1 [FIX·HIGH] 인스턴스 삭제 시 고아 데이터 + 인메모리 누수 + evictInstance 데드코드
- 위치: RegistryService.java:62-65(delete), OperatorInstanceOperations.release(풀/클라이언트만 닫음),
  HistogramSnapshotStore.evictInstance(호출자 0곳), OpsAlertDetector lastAlerted/lastDeadlockCount/lastDeadlockSig,
  SnapshotScheduler.backoff. 마이그레이션에 ON DELETE CASCADE 0건.
- 근거: 삭제해도 query_snapshot·plan_snapshot·backup_run·health_sample row와 인메모리 맵이 영구 잔존 → 장기가동 누수.
  ShedLock은 인메모리 상태를 공유하지 않음. https://github.com/lukas-krecan/ShedLock
- 수정: (a) 신규 마이그레이션 V10 — 자식 테이블 instance_id에 FK ON DELETE CASCADE(또는 delete()에서 명시 삭제,
  트랜잭션). (b) delete() 경로에 HistogramSnapshotStore.evictInstance + OpsAlert/backoff 맵 evict 훅 배선.
  각 컴포넌트에 evict(instanceId) 메서드 추가. 단위: 삭제 후 맵 키 제거 확인.

### B-2 [FIX·MED] plan_snapshot 보존 잡 부재 → 무한 성장
- 위치: PlanChangeTracker append만, 정리 잡 없음. query_snapshot·health_sample만 보존 잡 존재.
- 수정: (instance,query)별 최신 N개만 남기는 보존 잡 추가(기존 RetentionJob 패턴 재사용).

### B-3 [FIX·MED] 시작 지터 상한 없음 (이번 4차 아크 도입)
- 위치: SnapshotScheduler.java:103 `(order++)*JITTER_STEP_MS` 캡 없음.
- 근거: 인스턴스 많으면 후순위가 수 초~수십 초 sleep하며 워커 슬롯 점유 → collect 벽시계 팽창.
  https://dev.to/dixitgurv/spring-boot-scheduling-best-practices-503h
- 수정: `Math.min((order % workers) * JITTER_STEP_MS, cap)` 형태로 유한화.

### B-4 [FIX·MED] Future.get 예외 처리가 ExecutionException을 인터럽트로 뭉갬 (4차 아크 도입)
- 위치: SnapshotScheduler.java:107-113 `catch(Exception e){ interrupt(); }`.
- 근거: ExecutionException까지 삼키고 sched 스레드 인터럽트 플래그 세움. InterruptedException만 플래그 복원해야.
  https://www.yegor256.com/2015/10/20/interrupted-exception.html
- 수정: `catch(InterruptedException){interrupt();break;} catch(ExecutionException e){log.warn}` 분리.

### B-5 [FIX·MED] WebhookNotifier deliver()가 synchronized 안에서 HTTP (4차 아크 도입)
- 위치: WebhookNotifier.java synchronized send→deliver(client.send, ~8s).
- 근거: 웹훅 느리면 모든 폴러 send 직렬 블로킹 → 알림 폭주 시 폴러 동반 지연.
- 수정: 락 구간을 레이트리밋 판정까지로 좁히고, deliver는 락 밖(판정 결과만 락 안에서 결정, 전송은 밖).
  단위 유지(sendAt 결정 로직 불변).

### B-6 [FIX·LOW] @PreDestroy 종료 순서 경합
- 위치: SnapshotScheduler.java:69-80 vs taskScheduler 빈 파괴 순서 미보장.
- 수정: collect 진입/submit에 pool.isShutdown() 가드.

### B-7 [FIX·HIGH] 인스턴스별 HikariCP 풀 max=2를 폴러 8개+가 공유 → 허위 장애 증폭
- 위치: ConnectionPools.java:70 `setMaximumPoolSize(2)`. 같은 인스턴스에 SnapshotScheduler(+워커4)·OpsAlert·
  SLO·Backup·Anomaly·Regression·Advisor·ScoreService가 독립 접근.
- 근거: 3번째 동시 요청부터 3초 대기 후 SQLException → SnapshotScheduler가 정상 인스턴스를 죽은 대상으로
  오인해 최대 16분 백오프 + 허위 "수집 정지" 경보. 이번 4차 병렬화가 경합을 키움.
  https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
- 수정: maxPoolSize를 설정값(dbtower.pool.max-per-instance, 기본 6)으로 상향. connectionTimeout도 여유 확인.
  단위/실측: 병렬 워커에서 허위 백오프 미발생.

### B-9 [FIX·LOW] 웹훅 @everyone 멘션 인젝션 (WS-A에서 이관)
- 위치: WebhookNotifier — 사용자 텍스트가 Discord/Slack content로, allowed_mentions 없음.
- 수정: Discord payload에 `"allowed_mentions":{"parse":[]}` 추가. 제어문자 이스케이프 보강(\r 등).

### [SKIP] 보존 DELETE 단일 트랜잭션 + 인덱스
- 근거: 데모 규모(5대·주 단위)에선 실부하 낮음. 배치 삭제·brin 인덱스는 향후. 로드맵에 "대규모 시 배치화"로 문서화.

### [SKIP] ShedLock 쿨다운/델타 노드 로컬
- 근거: 이미 코드 주석이 인정한 한계. 완전 해소는 쿨다운을 메타 DB로 외부화(큰 변경) — 향후. 문서화 유지.
  단, PG 데드락 델타 "노드 전환 시 미탐"은 주석보다 강하므로 그 사실만 주석에 1줄 보강(B-B에서).

---

## WS-C 기종정확성·타임존

### C-1 [FIX·HIGH] MySQL slowQueries 초 단위 절삭 → 1초 미만 0ms
- 위치: MySqlOperator.java:326 `TIME_TO_SEC(query_time)*1000`.
- 근거: TIME_TO_SEC 정수 반환 + log_output=TABLE은 마이크로초 미저장. https://dev.mysql.com/doc/refman/8.0/en/slow-query-log.html
- 수정: `TIME_TO_SEC(query_time)*1000 + MICROSECOND(query_time)/1000`으로 보정(TABLE의 한계는 주석에 정직 표기).
  docker-compose에서 마이크로초 보존 위해 slow log FILE 전환은 범위 밖 — TABLE 내 최선으로.

### C-2 [FIX·MED] MySQL 동일 DIGEST 멀티스키마 중복/오폴백
- 위치: MySqlOperator.java latencyPercentiles top 쿼리(DIGEST GROUP BY 없음), histogramStore.swap 중복 키.
- 근거: (SCHEMA_NAME,DIGEST) 키라 한 digest가 두 스키마면 tops 2행 + 스냅샷 키 충돌 → 허위 "학습 중".
- 수정: top 쿼리에 `GROUP BY DIGEST`(타이머 SUM, 텍스트 MAX) 또는 스냅샷 키를 schema+digest로. 단위 추가.

### C-3 [FIX·MED] PG deadlockCount 형제 DB 사각 + 리셋 시 음수 델타
- 위치: PostgresOperator.java:657-666(current_database()만), OpsAlertDetector 델타.
- 근거: 같은 클러스터 다른 DB 데드락 안 보임 + pg_stat_reset 시 카운터 감소 → 음수 델타.
  https://www.postgresql.org/docs/current/monitoring-stats.html
- 수정: `SUM(deadlocks)` 전 비템플릿 DB 집계(주석: per-DB 귀속 상실) + OpsAlert에서 음수 델타를 리셋으로 간주(클램프).

### C-4 [FIX·HIGH] Oracle queryStats/slowQueries가 모니터 CURRENT_SCHEMA로 필터
- 위치: OracleOperator.java:58·86 `parsing_schema_name = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')`.
- 근거: 모니터≠앱 스키마면 앱 SQL 전멸. https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/V-SQL.html
- 수정: 스키마 필터를 설정값(dbtower.oracle.app-schema, 미설정 시 SYS/SYSTEM만 제외)으로. 단위/주석.

### C-5 [FIX·LOW] Oracle PHV:0 허위 플립
- 위치: OracleOperator.java planShapeForDigest, plan_hash_value=0을 실 shape로.
- 수정: PHV=0이면 empty 반환(계획 미포착 표기), 허위 플립 방지.

### C-6 [FIX·HIGH] 타임존 미고정 — LocalDateTime.now() 전면, UTC 강제 없음
- 위치: application.yml(설정 부재), 전역 LocalDateTime.now().
- 근거: 노드 서버 TZ 상이 시 스냅샷정지 오탐·쿨다운 어긋남. https://reflectoring.io/spring-timezones/
- 수정: application.yml에 `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` + DbtowerApplication에서
  `TimeZone.setDefault(UTC)`(main 초기). 저장 컬럼 타입 변경(Instant)은 범위 밖 — TZ 고정으로 노드 간 일관성 확보.
  주의: 데모 로그 시각이 UTC로 바뀌므로 주석/문서에 표기.

### [SKIP] 버전 비호환(MySQL 5.7 / PG≤12), Oracle v$ vs gv$(RAC), Mongo 마지막 버킷, MSSQL stdev MAX
- 근거: 대상 버전 명시(8.0/13+), 데모 단일 인스턴스 Oracle, ESTIMATED 라벨이 완충. 로드맵에 "지원 버전 전제"로 문서화.

### [SKIP] pg_stat_monitor 2.0.0 resp_calls(추정)
- 근거: 단일 패치 릴리스, 게이트 미통과 시 ESTIMATED로 정직 폴백. 실익 낮음.

---

## 검증(라이브 + 스크린샷) 계획
- C-1: sub-second 슬로우 쿼리가 실제 ms로 나오는 것(이전 0 → 이후 실측) API/카드 스크린샷.
- B-1: 인스턴스 삭제 후 인메모리 맵 키 제거를 로그/테스트로, 삭제 시 자식 row 정리 SQL 카운트.
- A-1/A-2/A-3: 단위 테스트 그린(XXE 페이로드 거부·스택쿼리 거부·prod 키없음 실패) 터미널 스크린샷.
- B-3/B-4/B-5: collect 로그 스레드/지터, 알림 비동기.
- C-3/C-4/C-6: PG 데드락 집계·Oracle 스키마·UTC 시각 로그.
- 회귀 없음: 전체 테스트 그린 + 기존 기능(레이턴시·데드락 카드) 정상 스크린샷.

## 블로그
- 16편 "감사가 찾은 것들을 실제로 고치다 — 하드닝 아크"(개선 서사). 특히 "내 코드가 이미 정답을 알고 있었다"
  (DeepAnalyzer XXE vs 파서 3곳)와 "병렬화가 되살린 함정"(HikariCP·지터)을 소재로.
