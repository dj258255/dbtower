# DBTower — 이기종 DBMS 운영 관리 플랫폼 (발표/포트폴리오 문서)

한 줄 요약: MySQL / PostgreSQL / SQL Server / Oracle / MongoDB를 하나의 인터페이스(DbmsOperator) 뒤에 등록하고,
모니터링 -> 시점 비교 -> 실행계획 분석 -> 회귀 자동 감지 -> 알림까지를 한 플랫폼에서 처리하는
컨트롤 플레인. Java 21 + Spring Boot.

모든 수치는 실측이며 재현 로그가 docs/VERIFICATION.md에 있다.

---

## 1. 왜 만들었나 — 문제 정의

DB가 여러 대, 여러 기종이 되는 순간 운영 작업이 이렇게 변한다:

- 쿼리 통계를 보려면 MySQL은 performance_schema, PostgreSQL은 pg_stat_statements,
  SQL Server는 DMV — **기종마다 다른 화면, 다른 SQL, 다른 용어**
- 지표가 여러 도구에 분산 — 모니터링은 Grafana, 슬로우 쿼리는 로그, 실행계획은 각 DB 콘솔
- "어제는 괜찮았는데 오늘 왜 느리지?"에 답하려면 사람이 여러 창을 띄우고 눈으로 비교
- DB가 1대 늘 때마다 이 수작업이 그대로 1대분 늘어난다

이건 개인의 숙련도 문제가 아니라 구조 문제다. 해법은 SRE의 원칙과 같다 —
**정형화된 운영 작업을 자동화해서, 관리 대상 수와 필요 인력이 비례하지 않게 만든다(DBRE)**.

같은 문제의식을 한 DB팀이 밋업에서 발표했다("개발팀이 스스로 분석 못 해 전부 DB팀 문의
-> 커뮤니케이션 비용"). DBTower는 그 문제 정의를 출발점으로, 1인이 검증 가능한 규모에서
핵심 메커니즘을 전부 직접 구현한 프로젝트다.

## 2. AS-IS -> TO-BE

```
AS-IS: 이슈 발생 -> 기종별 콘솔 각각 접속 -> 통계 SQL을 기종별로 기억해서 실행
       -> 눈으로 어제와 비교 -> 원인 추정 -> (모르면 더 숙련된 사람에게 질문)

TO-BE: 이슈 발생 전에 플랫폼이 회귀를 자동 감지해 웹훅으로 알림 (AI 1차 분석 첨부)
       이슈 발생 시 한 화면에서: 시점 비교(증감·신규 쿼리) -> 클릭 -> EXPLAIN + 규칙 분석
```

## 3. 아키텍처 — 추상화가 전부다

```
                        +--------------------------- DBTower (Spring Boot) ---------------------------+
   운영자 / Web UI ---> |  REST API                                                                  |
   회귀 감지 폴러 ----> |  ComparisonService(시점 비교) / RegressionDetector / BackupPoller          |
                        |                          |                                                 |
                        |                   DbmsOperator (인터페이스)                                |
                        |   health / queryStats / slowQueries / explain / tableStats /               |
                        |   backup / replicationState                                                |
                        |      |                   |                    |                            |
                        | MySqlOperator     PostgresOperator      MsSqlOperator                      |
                        +------|-------------------|--------------------|----------------------------+
                               v                   v                    v
                        performance_schema   pg_stat_statements   sys.dm_exec_query_stats
                        SHOW REPLICA STATUS  pg_stat_replication  AlwaysOn DMV
                        mysqldump            pg_dump              BACKUP DATABASE
```

플랫폼 코드(비교·감지·백업·API)는 DbmsOperator만 안다. 기종 분기는 팩토리 한 곳뿐.
새 기종 추가 = Operator 구현체 1개 추가이며 플랫폼 코드는 수정이 없다.

같은 "백업"도 내부는 전혀 다르다 — MySQL/PG는 클라이언트 도구(mysqldump/pg_dump) 실행,
SQL Server는 서버가 직접 파일을 쓰는 SQL(BACKUP DATABASE). 추상 정책(주기·보관)은 공통으로
설계하고 실행만 기종별로 갈라진다. 인사말 인터페이스에 한국어/영어 구현이 붙는 것과 같은 구도.

플랫폼 저장소는 PostgreSQL — 그리고 DBTower 자신을 관리 대상으로 등록한다(도그푸딩, 6장).

## 4. 핵심 기능 1 — 시점 비교 (문제 쿼리 식별)

"상위 쿼리 목록"만으로는 장애 원인을 못 찾는다:
- 평상시에도 꾸준히 상위였던 쿼리일 수 있고
- 평소 낮던 쿼리가 튀어오른 것일 수 있고
- 새로 유입된 쿼리일 수도 있다

그래서 "평소 구간 vs 문제 구간"을 쿼리 단위로 비교해야 한다.

구현 원리: 각 기종의 쿼리 통계는 서버 기동 이후 **누적 카운터**다. 1분마다 스냅샷을 찍어두면,
구간 양 끝 배치의 차분 = 그 구간에 실제 발생한 양. 구간 길이가 달라도 비교되도록 QPS로 정규화하고,
base에 없던 queryId는 신규 쿼리로 표시한다. rows/call 변화는 실행계획 변화(인덱스 이탈,
IN절 폭증)의 대리 신호로 쓴다.

실측: 부하 주입 전/후 비교에서 점조회 QPS 증가·신규 풀스캔 쿼리 감지 (VERIFICATION 3절).
여기서 밟은 함정 — 구간 경계가 스냅샷 배치 시각을 1초라도 놓치면 발생량이 이전 배치에 흡수되어
delta가 0이 된다. 경계 처리 규칙을 DESIGN.md 3.4절에 문서화했다.

## 5. 핵심 기능 2 — EXPLAIN + 규칙 분석 + AI 1차 분석 (원인 분석)

쿼리를 지목했으면 "왜 느린가"로 넘어간다. 실행계획을 기종별로 받아
(MySQL: EXPLAIN FORMAT=JSON / PG: EXPLAIN (FORMAT JSON) / MSSQL: SHOWPLAN_XML)
규칙 기반으로 비효율 신호를 지적한다 — access_type=ALL, filesort, Seq Scan,
Nested Loop 안쪽 Seq Scan, Clustered Index Scan 등.

규칙마다 "왜 그게 신호인지"와 "언제 오탐인지"를 문서화했다(ai-analysis-rules.md).
예: 앞 와일드카드 LIKE가 인덱스를 못 타는 이유는 B+Tree가 정렬 순서로 시작점을 잡는 구조라서다
— 자작 DB(db-hobby)에서 LIKE 최적화를 직접 구현하며 확인한 원리. 작은 테이블의 풀스캔은
오히려 빠르다(순차 I/O < 랜덤 I/O x N)는 예외도 함께 둔다. 최종 판단자는 선택도다.

AI 1차 분석은 이 규칙 문서를 그대로 system 프롬프트에 넣는다. "AI에게 판단을 맡기는" 게 아니라
**사람이 정한 판단 기준 위에서 AI가 1차로 훑게** 한다 — 같은 입력에 일관된 판정이 나오게 하는
프롬프트 설계이고, 레퍼런스가 같은 접근을 쓴다. API 키가 없으면 조용히 비활성화되어
규칙 기반 알림만 발송된다(분석 실패가 알림을 막지 않는다).

## 6. 성능 개선 아크 — 만들고, 측정하고, 고쳤다

| # | 문제 | 개선 | 실측 |
|---|---|---|---|
| 1 | 수집마다 DriverManager 새 커넥션 | 인스턴스별 HikariCP 풀 | 수집 47.1 -> 11.8ms (MySQL, 4.0배) |
| 2 | JPA saveAll이 행마다 INSERT | JDBC batchUpdate + reWriteBatchedInserts | 행당 1.51 -> 0.11ms (PG, 13.8배) |
| 3 | 스냅샷 조회가 Seq Scan | 복합 인덱스 (등치 컬럼 선두) | 50만 행 21.269 -> 0.062ms (343배) |
| 4 | 긴 쿼리 digest 병합(식별 불가) | max_digest_length 1024 -> 4096 | side-by-side로 병합/구분 재현 |
| 5 | 전체 부하 검증 | k6 10 VU 30s | 2,832 req/s, P95 5.86ms, 실패 0 |

아크 3이 이 프로젝트의 자기증명이다 — **DBTower 자신을 관리 대상으로 등록하고, DBTower의 explain
API로 자기 쿼리를 진단해 Parallel Seq Scan을 발견, 인덱스 추가 후 같은 API로 개선을 확인했다.**
등치 컬럼(instanceId)을 복합 인덱스 선두에 두는 이유까지 문서에 남겼다.

아크 4는 운영 지뢰의 사전 대응이다. MySQL digest는 기본 1024바이트까지만 정규화하므로
앞부분이 같은 긴 쿼리들이 하나로 뭉개진다. 기본값 서버와 4096 서버를 나란히 띄워
같은 쿼리 쌍이 한쪽에선 병합되고(executions=2, 꼬리 소실) 한쪽에선 구분되는 걸 재현했다.
부수 발견: 절단 기준은 정규화 텍스트 길이가 아니라 토큰 버퍼 바이트였다.
PostgreSQL은 파싱 결과 기반 digest라 이 이슈 자체가 없다 — 같은 "쿼리 통계"도 기종 내부가
이렇게 다르다는 것이 DbmsOperator 추상화가 필요한 또 하나의 근거.

## 7. 핵심 기능 3 — 회귀 자동 감지 + 웹훅 (사람이 보기 전에)

시점 비교를 사람이 구간을 골라 돌리는 대신, 플랫폼이 주기적으로
"최근 구간 vs 직전 베이스라인"을 스스로 비교한다. 감지 규칙 4개(쿼리별 쿨다운):

1. 신규 쿼리 유입
2. 호출량 급증 (QPS +200%)
3. 레이턴시 회귀 (평균 +200%)
4. 읽는 행수 폭증 (rows/call +500% — 플랜 변화·IN절 폭증의 대리 신호)

이 규칙들은 실제 장애 패턴에서 왔다 — 그 밋업의 활용 사례 3건(신규 배포 쿼리 유입 /
대규모 알림으로 전 쿼리 QPS 증가 / IN절 2000건 유입으로 Latency·RowExamined 폭증)이
정확히 이 규칙들에 대응한다. 레퍼런스에서는 사람이 화면에서 발견하는 것을 DBTower는 자동으로 잡는다.

E2E 실측: 점조회 베이스라인 2,249회 -> 신규 LIKE 풀스캔 + 점조회 급증 주입 -> 폴러가
findings=2 감지 -> AI 1차 분석 첨부 -> Discord 웹훅 HTTP 204 발송 (실패 0).
웹훅 어댑터도 이기종 — URL로 Discord/Slack 포맷을 자동 전환한다.

## 8. Lessons Learned — 밟은 지뢰들

- **pg_stat_statements는 클러스터 전역이다**: 같은 서버의 다른 DB 쿼리까지 섞여 들어온다.
  dbid 필터(current_database의 oid)로 격리 — 멀티테넌트 서버에서 필수.
- **digest 절단**(아크 4): 기본 1024에서 긴 쿼리 병합. 절단 기준은 토큰 버퍼 바이트.
- **백업은 네트워크 관점 문제다**: 첫 백업 3건 중 2건 실패. 컨테이너 안 mysqldump에게
  호스트의 127.0.0.1은 자기 자신이 아니고, 호스트 pg_dump(14)와 서버(16)의 버전 불일치.
  실행 위치마다 접속 관점이 달라지는 현실을 플레이스홀더 명령 템플릿로 흡수했다.
- **보안은 리뷰로 조인다**: 명령 주입(토큰 분리 후 치환 + 허용문자 + 플래그 거부),
  비밀번호 argv 제거(MYSQL_PWD/PGPASSWORD), MSSQL 식별자 ]] 이스케이프,
  JDBC URL 파라미터 주입 방지(host/dbName 패턴 검증), 자격증명 응답 미노출.
- **시점 비교의 경계 함정**: 구간 경계가 배치 시각을 놓치면 delta=0. 경계 포함 규칙 문서화.

## 9. 채널 확장 — 사람의 화면과 AI의 도구 (확장4·5)

같은 코어를 채널만 바꿔 노출한다:

- **웹 UI (확장4)**: 활동 그래프에서 조회/비교 구간을 드래그로 선택 -> Top Query 증감(NEW 뱃지)
  -> 클릭 -> EXPLAIN + 규칙 지적 + AI 1차 분석. 의존성 0인 정적 SPA를 Spring Boot가 서빙 —
  java -jar 하나로 API부터 Web까지. 스크린샷: docs/images/webui/
- **MCP 서버 (확장5)**: JSON-RPC 2.0을 직접 구현한 stdio/HTTP 두 전송(코어 공유),
  도구 13종(compare·explain·wait_events·sessions·partitions·schema_diff 등 — 읽기만, kill은
  위험해서 도구로 만들지 않음). 회귀 알림이 push라면 MCP는 AI 에이전트가 필요할 때
  당겨쓰는 pull. 등록 한 줄: `claude mcp add --transport http dbtower http://localhost:8080/mcp`
- **AI 백엔드 자동 선택**: API 키 -> Anthropic SDK(운영), 키 없음 + claude CLI -> headless 호출(로컬).
  프롬프트는 stdin, 로컬 설정은 --setting-sources 빈값으로 격리.

## 10. 확장6 — 주장을 실측으로: Oracle과 MongoDB를 추가해 보다

3장에서 "새 기종 추가 = Operator 구현체 1개, 플랫폼 코드 수정 없음"이라고 주장했다.
주장은 검증해야 한다. 그래서 성격이 다른 두 기종을 골라 추가했다:

- **Oracle** — JDBC 계열이지만 통계 소스(V$SQL)·권한 모델·백업(DBMS_DATAPUMP 서버 사이드 API)이 전부 다른 상용 DB
- **MongoDB** — SQL도 JDBC도 없는 기종. explain 입력이 명령 JSON이고 통계 소스가 프로파일러 컬렉션

결과 (VERIFICATION 18절): 새로 만든 것은 Operator 구현체 2개와 Mongo 클라이언트 캐시,
기존 수정은 enum 값·팩토리 case 등 몇 줄. 스냅샷 수집·시점 비교·회귀 감지·웹 콘솔·MCP는
**0줄 수정**으로 5기종을 처리했다. MongoDB 부하 실측에서 신규 쿼리 NEW 감지와 구간 차분이
그대로 동작 — 추상화 경계를 SQL이 아니라 "운영 행위"에 그은 것이 맞았다는 증거.

백업 실행 모델은 4가지로 갈라졌다: 외부 CLI + 비밀번호 env(MySQL/PG), 외부 CLI +
비밀번호 stdin(MongoDB — 비밀번호 env가 없는 도구), 서버 사이드 SQL(MSSQL),
서버 사이드 API(Oracle). "비밀번호를 argv에 싣지 않는다"는 원칙은 네 모델 모두 유지.

덤으로 배운 것: Hibernate가 만든 enum CHECK 제약은 ddl-auto=update로 갱신되지 않는다.
기종 추가가 코드 배포가 아니라 스키마 마이그레이션이 되는 순간을 직접 밟았다 —
Flyway가 필요한 이유를 교과서가 아니라 이 프로젝트 안에서 만났다.

## 11. 로드맵 완주 — Phase A~E 전부 완료

10장 이후 로드맵 5단계를 전부 구현했다. 원칙은 매 단계 같았다 — "한계 인지 -> 개선 -> 실측 ->
남은 한계 정직 명시" (전 과정: VERIFICATION 19~52절).

- **Phase A 운영 안전 (9/9)**: 세션+토큰 이중 인증과 VIEWER/ADMIN 분리, 비밀번호 AES-256-GCM,
  Flyway 이관, 스냅샷 보존(7일), ShedLock HA 분산 락, 감사 로그(+Specification 검색), 백업 복원
  검증(VERIFIED/FAILED/UNSUPPORTED 3값), 최소 권한 계정 실측 확정, 분석 보호장치(쿼리 타임아웃 +
  수집 지수 백오프 — 진단이 부하 유발자가 되지 않게)
- **Phase B 진단 심화 (8/8)**: Wait Event 5기종 통합, 세션/블로킹 트리 + kill(ADMIN),
  HypoPG 가상 인덱스 어드바이저, gh-ost 온라인 DDL(기본 dry-run), 운영 알림(장기 트랜잭션·복제
  지연·수집 정지), 파라미터 드리프트(마스킹), Schema Diff, DB팀 문의 채널
- **Phase C 프로비저닝 (4/4)**: 멱등 등록 PUT(upsert)을 종점으로 K8s(CloudNativePG e2e 완주)·
  Ansible(멱등 changed=0)·Terraform(validate까지 — apply는 AWS 자격증명 필요라 미실행, 정직 표기)
- **Phase D 자율 진단 (10/10)**: "사람이 모는 대시보드에서 스스로 보는 관제탑으로" —
  (요일x시간대) 베이스라인 z-score 이상 감지, Advisors 운영 규칙 점검, 자연어 진단(AI가 read-only
  도구 화이트리스트를 자동 연쇄), SLO/에러 버짓, 레이턴시 백분위(기종별 NATIVE/COMPUTED/ESTIMATED/
  UNSUPPORTED 정직 구분), 파티션 조회, FinOps 신호(금액은 지어내지 않음), 백업 신선도, 통합 헬스
  스코어(0~100, 나쁜 순), 심층 원인 진단(EXPLAIN ANALYZE 실제 실행 — 추정 vs 실제 카디널리티
  괴리로 "왜 인덱스를 못 탔나" 근본 원인 5종 지목)
- **Phase E 셀프호스트 제품화**: SaaS의 네 벽(자격증명 수탁·사설망 도달·멀티테넌시·비용)을 피해
  Grafana/PMM식 셀프호스트로 — 배터리 포함 이미지(백업 CLI 번들), 원커맨드 compose, GHCR 릴리스

완주 후 심화 아크(VERIFICATION 54~56절)로 세 개를 더 팠다 — TLS 강제 접속(관리형 서비스 대응,
검증 우회 옵션은 일부러 없음), 백업 원격 보관(S3 호환 — 3-2-1의 오프사이트 완성), 실행계획 변경
감지(plan flip — 회귀 쿼리만 계획 shape 비교, PG는 GENERIC_PLAN).

이후 프로덕션 로드맵 Phase 2~5도 완주했다(VERIFICATION 72~82절, 전부 라이브 실측 포함) —
로그 백업 5기종+PITR(MSSQL·PG 실복원 e2e), 팀 스코핑 LBAC(스코프 밖은 404 — 존재 은닉)과
공유 세션, 디스크 포화 예측(잔량이 아니라 속도 — 여유 76.8%에서 치명 경보가 맞는 케이스 실증),
서버 공유 인지(같은 서버 경보 dedup — 위험 귀속은 유지), 그리고 수평 확장 3부작: 수집 샤딩
(노드 kill 시 전 샤드 인수 무중단), 분산 로그인 잠금(노드를 오간 실패도 하나의 임계),
메타DB 월별 파티셔닝(보존 정리 DELETE 1.9초 → DROP 12.8ms·블로트 0) + 커넥션 온디맨드.

남은 것은 의도적 잔여뿐이다(정직 명시): 쿨다운 설정 외부화, Vault 동적 계정, 백업 산출물 암호화.
(히스토그램 기반 구간 p95는 이후 v1.1.0 심화 아크에서 해소 — 플랜 플립 5기종·데드락 축·복제 슬롯,
그리고 하드닝 아크까지 VERIFICATION 57~62절 참고. 이후 갭은 ROADMAP "프로덕션 로드맵" 절이 승계.)
그리고 "범위 밖"(SQL 승인 워크플로, 자동 인덱스 생성, DBaaS 멀티테넌시)은
여전히 안 한다 — 대상 DB를 스스로 바꾸는 순간 다른 제품이 되기 때문이다.

## 부록 — 기술 스택과 근거

| 선택 | 근거 |
|---|---|
| Java 21 + Spring Boot 4 | JD 요구 스택. 가상 스레드 시대의 표준 백엔드 |
| Lombok 적재적소 | 값 객체는 전부 Java 21 record(QueryStat·QueryDiff 등) — Lombok의 주 용도가 언어 표준이 됐다. JPA 엔티티는 @Getter만(게터 보일러플레이트 제거, 정리 아크 R1 실측) — @Data/@ToString은 lazy 연관관계를 건드리는 고전적 지뢰라 배제 |
| JDBC 직접 사용 (Operator 계층) | 기종별 통계 뷰·관리 명령은 ORM의 추상화 대상이 아님 |
| JPA (플랫폼 메타데이터) + JDBC batch (스냅샷 쓰기) | 엔티티 관리는 JPA, 대량 쓰기는 batch — 적재적소 |
| PostgreSQL (플랫폼 저장소) | Operator 지원 기종이라 도그푸딩 가능 |
| Prometheus + Grafana | 시계열 저장/시각화는 표준 도구에 위임, 플랫폼은 쿼리 수준 분석에 집중 |
| Anthropic Java SDK | AI 1차 분석. 판단 기준은 프롬프트에 명시(ai-analysis-rules.md) |
| k6 | 부하 재현·성능 회귀 확인 |
