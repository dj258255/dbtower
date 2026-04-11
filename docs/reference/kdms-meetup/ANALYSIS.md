# 당근 DB 밋업 1회 분석 — "개발자를 위한 DB 분석 도구와 방법" (KDMS Database Insight)

- 영상: https://youtu.be/NrPY9J1a2ag (제목: 개발자를 위한 DB 분석 도구와 방법 | 당근 DB 밋업 1회)
- 슬라이드 캡처 59장: 이 디렉토리의 53.png ~ 111.png
- 이 문서의 목적: KDMS의 문제 정의 방식·기능 설계·운영 이슈를 분해하고,
  dbhub가 무엇을 이미 커버했고 무엇이 갭인지 1:1로 대조한다.
  (dbhub는 이 발표의 문제 정의를 출발점으로 설계된 프로젝트다)

당근 DB팀의 관리 대상은 채용공고 기준 MySQL / PostgreSQL / MongoDB / DynamoDB 4종.
KDMS 화면에는 Aurora MySQL / Aurora PostgreSQL / MongoDB 엔진 필터와
Alpha/Production 환경, KR/CA/GB/JP 리전 필터가 보인다.

---

## 1. 발표 구조 (슬라이드 순서 그대로)

### 1-1. KDMS란 (53~54)

- Karrot Database Management System — 다양한 DBMS의 "운영 자동화"를 위한 DB admin 사이트
- 기능 4개: Aurora DB 생성/모니터링 자동화, MySQL/PostgreSQL 파티션 자동 관리,
  Schema 형상 관리, **Database Insight (DB 부하 모니터링)** ← 이번 발표의 주제
- 시사점: KDMS는 "모니터링 툴"이 아니라 생성·파티션·스키마·부하를 묶은 **운영 플랫폼**이고,
  Database Insight는 그 하위 기능 하나다. 현대오토에버 JD의 "DBMS 운영 관리 플랫폼"과 같은 구도.

### 1-2. 개발 배경 — AS-IS / 문제점 / TO-BE / 목표 (55~59)

- AS-IS: 서비스 이슈 발생 → 개발팀이 Datadog/Sentry로 원인 분석 → Cloudwatch/Grafana로
  DB 지표 확인 → 그래도 모르면 **DB팀 문의** → DB팀이 Grafana/Cloudwatch/AWS PI/Slow Query Log
  종합 확인 → 조치
- 문제점 2개:
  1. 개발팀이 자체적으로 DB 이슈를 분석하기 어려워 대부분 DB팀 문의 → **커뮤니케이션 비용·시간**
  2. 지표가 여러 사이트에 분산 (Grafana / Cloudwatch Log / Performance Insight) → 전부 방문해야 함
- 가설: "한 곳에서 개발팀이 DB 이슈를 쉽게 분석할 수 있으면 커뮤니케이션 비용이 줄고 빠른 조치가 가능하지 않을까?"
- 목표: 개발팀이 DB팀 문의 없이도 분석 가능하게 / 개발팀·DB팀 모두 하나의 툴로
- 시사점: **문제 정의가 기술이 아니라 조직 비용(문의 티켓)에서 출발**한다. DBRE의 본질
  (인력이 서비스 수에 비례해 늘지 않게)과 정확히 일치. 발표·자소서에서 dbhub를 설명할 때도
  "기능 나열"이 아니라 이 구도(반복 문의 → 셀프서브 분석)로 말해야 한다.

### 1-3. 기존 툴 분석 — AWS Performance Insight의 한계 (60~63)

- 새 기능을 만들기 전에 기존 툴부터 분석했다 (개발팀이 어디서 막히는지 → 무엇을 제공할지)
- AWS PI의 문제 1: **용어가 DBA 전용** — redo_log_flush, MDL_lock, wait/io/handler 같은
  wait event는 개발팀에게 낯설다
- AAS(Average Active Session) 개념: 일정 기간 평균 동시 실행 세션 수.
  1분간 A 쿼리를 수행한 세션이 120개면 AAS = 120/60 = 2
- AWS PI의 문제 2: **상위 SQL(AAS 순 정렬)만으로는 원인 쿼리를 못 찾는다**
  - 평상시에도 꾸준히 AAS가 높은 쿼리였을 수 있음
  - 오히려 평소 부하가 낮던 쿼리의 AAS가 증가하며 문제를 유발했을 수 있음
  - 새롭게 나타난 쿼리가 문제였을 수 있음
- 실제 Slack 문의 사례 2건: "두번째 쿼리 횟수가 갑자기 튀었는지 확인할 수 있나요?",
  "특정 시점에 어떤 쿼리가 많이 발생했는지, CPU 스파이크에 어떤 쿼리가 영향을 준 건지"
  → 문의의 본질 = **특정 쿼리의 호출수 증가량 / 특정 시점의 문제 쿼리 식별** — 상위 SQL만으로는 불가
- 결론: 평소 시점과 장애 시점을 비교해서, 쿼리별로 지표가 어떻게 변했는지(QPS/Latency 등)를
  알아야 한다 → **시점 비교 기능 필요**
- 시사점: "왜 시점 비교인가"의 논증 사슬이 발표의 백미. dbhub 발표 때도 같은 사슬로:
  누적 카운터 스냅샷 → 구간 diff → 증감률 → 신규 쿼리 감지.

### 1-4. Database Insight 주요 기능 (64) — 3단계 워크플로

1. **문제 쿼리 식별**: 상위 SQL 표시 / 시점 비교 / 쿼리별 지표 증감률 / 신규 쿼리 표시 / 모니터링 지표 통합
2. **원인 분석**: 실행계획(Explain) / 스키마·통계정보 / AI 분석
3. **DB팀 문의**: Slack 쓰레드 생성 / KDMS Link / 쿼리 분석 결과 첨부
- 시사점: 기능이 아니라 **사용자의 문제 해결 여정** 순서로 묶었다. 문의는 "없애는" 게 아니라
  "분석 결과가 첨부된 고품질 문의"로 바꾸는 것 — 셀프서브가 실패해도 가치가 남는 설계.

### 1-5. 화면 데모 (65~78)

- 진입: Environments(Alpha/Production) x Engines(Aurora MySQL/Aurora PostgreSQL/MongoDB) x
  Regions(KR/CA/GB/JP) 필터 → Cluster 검색 → Instance 선택 (Primary | db.t4g.medium | 3.10.4 표시)
- 시간대 선택: 조회 시간대 + 비교 시간대를 각각 입력 (1시간/3시간/12시간 프리셋),
  **CPU 그래프에서 드래그로 두 구간을 직접 선택** (조회=초록, 비교=주황 하이라이트)
  - CPU 그래프를 쓰는 이유: PI의 wait event보다 개발팀이 직관적으로 DB 상태 확인 가능 (74)
- 탭 3개:
  - **Top Query**: Load(AAS) | Query | Call/sec | Latency(ms) | Row Examined(Avg).
    비교 조회 시 각 지표에 증감(▲/▼)이 붙고, 신규 쿼리는 NEW 뱃지 + 분홍 하이라이트 (77~78)
  - **Slow Query**: Timestamp | User@host | Query_time | Lock_time | Row_sent | Query (69)
  - **Monitoring**: CloudWatch Metric(CPU/Connections) + Grafana 패널(Queries/SELECT/UPDATE/
    INSERT/DELETE Commands, Prepared Statement Calls)을 한 화면에 임베드 (70~72)
- 부가 정보: Slack Group(Team @dba / On Call @db-oncall), AWS PI 바로가기 링크
- 쿼리 클릭 → SQL ID + 전문 + **실행계획 보기** 버튼 → 실제 DB에 접속해 EXPLAIN 실행한 결과 표시 (79~80)
- 실행계획 아래 **AI 분석**이 자동 첨부: 쿼리 분석 결과(효율/비효율) + 분석 내용 + 튜닝 방안 (81)
- **테이블 상세 정보**: CREATE TABLE 스키마, 기본 통계(엔진/행수/데이터·인덱스 크기/평균 행 길이),
  인덱스 정보(컬럼/타입/카디널리티) (82~84)

### 1-6. 시점 비교 활용 사례 3가지 (89~91) — 실전 스토리

1. **신규 쿼리 유입**: 기존 쿼리 QPS 증가 + 신규 쿼리 2개가 QPS 100으로 유입 확인
   → 개발팀이 신규 배포 기능이 문제임을 인지 → 롤백 후 호출 수 조절하여 재배포
2. **기존 쿼리 호출 수 증가**: 전반적으로 모든 쿼리의 QPS 증가 (SET autocommit +1543%)
   → 특정 쿼리 문제가 아니라 대규모 알림 전송으로 인한 트래픽 증가임을 인지 → 알람 push 속도 조절
3. **쿼리 Latency 증가**: 특정 쿼리 Latency +5.98ms, Row Examined 평소 대비 2000건 이상 증가
   → IN절 조건이 기존 1~2개였는데 그 시점부터 2000건 이상인 쿼리 유입
   → IN절 조건 개수 제한 후 재배포
- 시사점: 3가지 사례가 곧 회귀 감지의 3규칙(신규 쿼리 / QPS 급증 / Latency+rows 증가)이다.
  dbhub RegressionDetector의 4규칙과 정확히 겹친다 — dbhub는 이걸 사람이 보기 전에
  자동으로 잡아 웹훅으로 쏘는 것까지 나갔다.

### 1-7. AI 분석 설계 (92) — 프롬프트에 판단 기준을 박는다

- "AI는 실행 시마다 결과가 달라질 수 있으므로, 일관되고 정확한 답변을 위한 프롬프트 설계"
- **DB 엔진마다 쿼리 효율/비효율을 판단하는 당근 DB팀의 판단기준을 프롬프트에 명시**:
  - PostgreSQL: Seq scan 작업이 필요한 경우 / Index Scan으로 1000건 이상 읽으면서 Limit 조건이 없는 경우
  - MySQL: 실행계획 type이 ALL 또는 index인 경우 / "Using temporary" 또는 "Using filesort"를
    가지고 있는데 rows 필드의 전체 합이 1000 이상인 경우
- 시사점: dbhub의 docs/ai-analysis-rules.md + AiAnalyzer 구조와 **완전히 동일한 접근**.
  dbhub는 여기에 "각 규칙의 원리를 db-hobby 구현 경험으로 소명"까지 얹었다 — 발표 차별점.

### 1-8. DB팀 문의 통합 (93)

- 실행계획 화면에 "슬랙으로 문의하기(#_infra_db)" 버튼 → KDMS 앱이 Slack 쓰레드 자동 생성:
  요청자 / DB 인스턴스 / 요청 쿼리 전문 / KDMS Link(비교 구간·sql_token_id가 박힌 딥링크) / 실행계획(댓글)
- 시사점: 문의가 와도 DB팀이 컨텍스트를 다시 물을 필요가 없다. "문의 비용"을 양쪽에서 줄임.

### 1-9. Lessons Learned — 운영에서 밟은 지뢰 3개 (94~102)

1. **digest 저장 길이 이슈 (MySQL)**: 상위 SQL은 digest(정규화)된 쿼리인데, digest가 전체 쿼리가
   아니라 max_digest_length(기본 1024 byte)만큼만 잘라서 수행됨 → 앞부분이 같은 긴 쿼리들이
   같은 digest로 합쳐져 문제 쿼리 식별 불가
   - 해결: max_digest_length / performance_schema_max_digest_length 1024 → 4096
   - 메모리 영향 계산까지 제시: max_digest_length는 세션당 1회 할당(동시세션 1000 기준 약 3MB 증가),
     performance_schema쪽은 events_statements_history(세션별 10건) + history_long(10,000건) +
     summary_by_digest(10,000건)의 DIGEST_TEXT 증가분 합계 약 90MB → "변경해도 부담 크지 않음"
2. **digest 저장 건수 이슈 (MySQL)**: events_statements_summary_by_digest는
   performance_schema_digests_size(기본 10,000)만큼만 저장 → 가득 차면 신규 쿼리 통계가 안 쌓여
   PI에서 통계정보가 표시되지 않음 (AWS 경고 문구 실제 발생)
   - 해결: 파라미터 10,000 → 20,000 + **80% 이상 차면 자동 Truncate**
3. **PostgreSQL은 같은 이슈가 없다**: PG는 SQL 텍스트가 아니라 **파싱 완료된 결과를 기반으로
   digest**하므로 길이 무관하게 구분됨. pg_stat_statements.max(기본 5,000) 초과 시
   덜 쓰인 쿼리를 자동 제거하고 신규를 저장 → 별도 파라미터 설정 불필요
4. **prepared statement 이슈 (MySQL)**: PS 사용 시 PI에서 SQL_ID가 digest hash가 아닌
   PI- prefix로 표시되고 QPS/Latency 등 통계정보 자체가 안 보임
   - 배경: MySQL/PostgreSQL의 PS는 세션(커넥션) 로컬이라 타 세션에서 재사용 불가
     (Oracle/SQL Server는 인스턴스 공유). 커넥션 수 x 쿼리 수만큼 서버 메모리 사용
     (10 파드 x 50 커넥션 x 쿼리 1개 = 500번 중복 캐싱)
   - 매 쿼리마다 prepare → execute → close를 반복하면 오히려 비효율.
     PS가 효율적인 조건 = 한 커넥션에서 prepare 1회 → execute N회 → close 패턴,
     커넥션 수 적고 쿼리 패턴이 단조로운 배치성 워크로드
   - 결론: **PS를 가급적 사용하지 않는 방향으로 가이드** (통계정보 확인 가능하도록)
- 시사점: dbhub도 max_digest_length 1024→4096을 실제로 밟고 VERIFICATION.md에 남겼다
  (MySQL Operator 구현 중 긴 쿼리 digest 잘림 확인). "같은 지뢰를 나도 밟았고 문서화했다"는
  발표에서 실무 감각의 증거가 된다. 2번(digests_size 가득참 대응)과 4번(PS 가이드)은
  dbhub가 아직 안 다룬 운영 포인트 — 문서에 규칙으로 추가할 가치.

### 1-10. MCP 활용 (108~111)

- KDMS 기능들을 MCP(Model Context Protocol)로 제공하는 이유: KDMS 웹뿐 아니라
  AI 에이전트가 KDMS 기능을 활용해 DB 분석할 수 있도록 (Slack·Agent·IDE 등 다양한 채널)
- MCP로 제공 중인 기능 6개: 시점 비교 분석(Performance Insights) / Wait Event 분석(Bottleneck
  Detection) / 실행 계획(EXPLAIN + 비용 트리 분석) / 모니터링 지표(Realtime Metrics) /
  Schema Diff(스키마 버전 간 차이 추적) / 파티션 조회(Partition Inventory)
- Slack에서의 활용 플로우: DB 알럿 쓰레드에 이모지 반응 → API 서버가 이벤트 수신 →
  LLM 라우터(Claude 호출) → MCP 서버가 KDMS 기능으로 필요 정보 확인 → 쓰레드 댓글로 분석 결과
  - 실제 사례: CPU 100% 알람 10분 지속 → AI가 모니터링 메트릭+Wait Event 동시 조회 →
    이상 시점 19:47 특정 → 정상/이상 구간 Top Query 비교 → "풀스캔 집계 쿼리 3종 동시 유입"
    결론 + 핵심 증거 테이블 첨부
- 단계별 보안·인증 3단계:
  1. 요청 검증 — API 서버: 허용된 채널·유저만 호출 가능, 이외 요청 응답 X
  2. 내부망 격리 — LLM 라우터·MCP 서버: 내부망 전용 배포, 외부 네트워크 직접 호출 불가,
     인증된 내부 서비스만 통신 허용
  3. 데이터 마스킹 — 응답: 쿼리·실제 고객 데이터 마스킹, 고객정보가 Slack 등 외부 노출 방지
- 시사점: "알럿 → 이모지 → AI가 MCP로 스스로 조회 → 근거 첨부 답변"은 dbhub 회귀감지+AI분석의
  대화형 확장판. dbhub는 감지 시점에 AI 분석을 밀어넣는(push) 구조, KDMS는 사람이 원할 때
  AI가 도구를 당겨쓰는(pull) 구조. MCP 서버화가 dbhub의 자연스러운 다음 확장.

---

## 2. KDMS vs dbhub — 1:1 대조표

| KDMS 기능 | dbhub 현황 | 비고 |
|---|---|---|
| 이기종 DBMS 등록 (Aurora MySQL/PG/MongoDB) | 완료 — MySQL/PostgreSQL/SQL Server, DbmsOperator 추상화 | 기종 구성만 다르고 구도 동일 |
| 상위 SQL (AAS/Load 정렬, QPS/Latency/RowExamined) | 완료 — queryStats (QPS/avgMs/rows per call) | AAS 대신 총시간 기반. AAS 개념은 문서로 소명 가능 |
| Slow Query 탭 | 완료 — slowQueries | |
| 시점 비교 (증감률 + NEW 뱃지) | 완료 — ComparisonService (change% + newQuery) | 로직 동일, 화면 없음 |
| 모니터링 지표 통합 (CloudWatch+Grafana 임베드) | 완료 — Prometheus + Grafana + exporters | |
| EXPLAIN 실행 + 결과 표시 | 완료 — explain() 기종별 | |
| 스키마/통계/인덱스 정보 | 부분 — tableStats (행수/크기). 인덱스 카디널리티·DDL 미포함 | 확장 여지 |
| AI 분석 (판단기준 프롬프트 명시) | 완료 — AiAnalyzer + ai-analysis-rules.md | 접근 동일 + db-hobby 근거 소명은 dbhub만의 차별점 |
| Slack 문의 쓰레드 자동 생성 | 부분 — 웹훅 알림(Discord/Slack)은 있음, 문의 딥링크 없음 | |
| 시점 비교 활용 사례 (사람이 발견) | 초과 달성 — RegressionDetector가 3사례를 자동 감지 (신규/QPS/Latency/rows 4규칙) | KDMS 발표엔 자동 감지 없음 |
| digest 길이 이슈 (1024→4096) | 완료 — 동일 이슈 실측, VERIFICATION.md 기록 | |
| digests_size 가득참 자동 Truncate | 미구현 | 운영 규칙으로 추가 가치 |
| prepared statement 가이드 | 미구현 (문서 여지) | |
| 백업 정책 (KDMS는 기능명만 언급) | 초과 달성 — 정책 CRUD + 폴러 + 기종별 실행 | |
| 복제 상태 | 초과 달성 — replicationState 3기종 | |
| **웹 UI (CPU 그래프 드래그, 탭, 증감 표시)** | **없음 — 최대 갭** | REST API + curl뿐 |
| **MCP 서버 (AI 에이전트 채널)** | **없음 — 두번째 갭** | AiAnalyzer는 push형만 |
| Wait Event 분석 | 없음 | 범위 밖으로 정리 가능 |
| 파티션 관리 / Schema Diff / DB 생성 자동화 | 없음 | KDMS 본체 기능, Insight 범위 밖 |

## 3. 결론 — 현대오토에버 대비 다음 액션 우선순위

1. **얇은 웹 UI 1~2화면** (효과 최대): 인스턴스 선택 → 시점 비교 결과 표(증감 색상 + NEW 뱃지)
   → 쿼리 클릭 시 EXPLAIN + AI 분석. KDMS 데모 화면의 축소판이자 JD의 "사용자에게 보여지는 Web까지" 충족.
2. **MCP 서버 확장** (차별점): compare/explain/health를 MCP tool로 노출 → Claude가 dbhub를
   도구로 써서 자연어 질의("어제랑 오늘 23시 비교해줘") 처리. KDMS의 최신 방향과 일치하고
   과제/면접에서 이야기가 된다.
3. **운영 규칙 문서 보강** (저비용): digests_size 80% Truncate, PS 가이드, AAS 개념을
   docs에 추가 — "발표에서 나온 지뢰를 전부 인지하고 있다"는 증거.
