# Changelog

이 프로젝트의 주요 변경을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를
따르고, 버전은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따른다.

셀프호스트 이미지는 GHCR에 게시된다: `ghcr.io/dj258255/dbtower`. 태그를 push하면
`.github/workflows/release.yml`이 규약·브라우저 E2E·전체 테스트를 게이트로 걸고 멀티아치
(amd64+arm64) 이미지와 GitHub Release를 게시한다.

## [Unreleased]

이번 회차는 AI를 화면 앞의 사람에게서 떼어낸다. 지금까지 AI(회귀 1차 분석·워크벤치 보조·자연어 진단·실행계획 분석)는
사람이 화면 앞에 있어야만 돌았고, "이 소견이 어떤 사실을 보고 한 말인가"를 되짚을 방법도, 모델이 지어낸 수치를
거를 방법도 없었다. 진단 종류 9종을 **하나의 작업 모델**로 묶어 Slack·웹 콘솔·경보·DB팀 문의 어디서 시작하든
같은 권한·사실·검증·감사를 거치게 했다. 모듈 17개, 테스트 960건(실패 0, 건너뜀 46), 실행면 테스트 32건.
라이브 실측은 [VERIFICATION 169절](docs/VERIFICATION.md).

### Added

- **AI 운영 작업 모듈(`aiops`)**(169절): 쿼리 진단·회귀 원인·백업 위험·SLO 위험·Advisor 요약·비용 검토·장애 초기 진단·DB팀 문의·정기
  리포트 9종을 한 작업 모델로 묶는다. 상태는 `RECEIVED -> AUTHORIZED -> COLLECTING -> (RETRIEVING) -> ANALYZING -> VERIFYING -> COMPLETED`
  이며 전이는 `AiOperationStatus` 한 곳에 둔다. 승인 대기 상태는 두지 않았다 — 변경 승인은 review 모듈이 단일 권위라, 결과에
  `approvalRequired`만 남기고 기존 변경 요청 티켓으로 보낸다. 유형이 곧 수집 범위라 요청 문장으로 범위를 넓히지 않는다.
- **모델 소견 검증기(`ClaimVerifier`)**: 소견의 수치와 인용을 플랫폼이 모은 사실·규칙 판정·참고 자료·요청 문장과 글자로 대조해
  어긋나면 `unverifiedClaims`로 남긴다. 거부하지 않고 표시한다 — Slack·콘솔은 이 목록을 소견보다 먼저 보여준다. 실측에서
  시각 오탐 6건과 단위 붙은 수치 누락을 잡아 고쳤다.
- **입구 넷**: Slack 한 문장(멘션·슬래시 명령), 웹 콘솔 AI 칸의 "작업으로 맡기기"(유형·구간 선택), 경보 자동 트리거(기본 꺼짐),
  DB팀 문의 뒤 사실·규칙·AI 소견 첨부(기본 켜짐). 접수 주체는 위조를 막기 위해 본문이 아니라 인증 주체로 기록한다.
- **실행면(`integrations/ai-ops-gateway`)**: 게이트웨이(Slack 서명·허용 채널 확인·접수·스레드 답글), 릴레이(Outbox 선점 -> Redis
  Streams), LangGraph 실행기(선점·사실·검색·분석·알림). 권한·사실·검증·감사는 플랫폼이, 큐·재시도·참고 자료 검색·알림은
  실행면이 맡는다 — 플랫폼은 Redis를 모른다. Slack 서명 확인 응답 65.6ms, 결과 도착 31.7초.
- **n8n 완료 웹훅 워크플로**: `integrations/n8n/dbtower-ai-operations.json`. 서명(HMAC)과 5분 만료를 n8n 안에서 다시 확인하고,
  분기는 DBTower가 확정한 `status`·`approvalRequired`로만 한다. 컨테이너에 가져와 활성화하고 위조 서명이 분기를 타지 않는 것까지 확인했다.
- **웹 콘솔 AI 운영 작업 카드와 접수**: 진단 탭에 작업 카드를 넣어 상태(사실 수집 -> AI 분석 -> 완료)와 결과를 보여주고,
  결과 링크를 `/?aiop=<작업id>` 딥링크로 바꿨다. 관제 오른쪽 AI 칸에서 유형·구간을 골라 접수한다.
- **운영 지표와 Grafana 대시보드**: `dbtower_aiops_jobs_active{status}`·`dbtower_aiops_outbox_unpublished`·
  `dbtower_aiops_jobs_stalled_seconds`·`dbtower_aiops_jobs_finished_total{type,status}`·`dbtower_aiops_jobs_duration_seconds_*`.
  게이지는 스크레이프마다 DB를 때리지 않고 캐시 갱신 주기로만 읽는다.
- **정기 리포트와 팀별 채널 표**: 주 1회 팀별 운영 요약(`periodic-report`, 기본 꺼짐), 결과를 돌려보낼 채널을 팀 표로 받는다
  (`channels.default`·`channels.by-team`). 표에 없는 팀은 Slack으로 보내지 않는다.
- **참고 자료 검색(실행면, pgvector)**: 운영 문서를 절 단위로 적재해 벡터 유사도와 문자 2-gram을 섞어 순위를 매긴다. 1차 거름은
  점수가 아니라 메타데이터(문서 용도·절의 기종)다.
- **마이그레이션 V42~V44**: `ai_operation_job`, `ai_operation_result`, `ai_operation_outbox`.

### Changed

- **로그인 화면**(172절): "AI가 만든 것 같다"는 말을 들은 요소를 걷어냈다 — 세 색 광원 배경, 빈 배경 한가운데 떠 있는 유리 카드,
  광택 강조 버튼, 구분선 밑 불릿 안내. 단색 하늘 배경에 아이디·비밀번호를 한 묶음으로 두고 로그인은 칸 안의 화살표 버튼으로 한다.
  부제("이기종 DBMS 운영 관리 플랫폼")를 뺐다. admin 비밀번호 안내는 입력 바로 아래 상자에 코드와 맞게 고쳐 적었다 —
  환경변수가 비밀번호를 **정하고**, 없으면 첫 기동 때 로그에 한 번 찍힌다(전 문구는 "환경변수를 확인하세요"라 이 구분이 없었다).
  네 곳에 흩어져 뒤의 것이 앞의 것을 덮던 로그인 CSS를 한 블록으로 모았다.
- **관제 AI 어시스턴트 칸을 채팅으로**(173절): 질문을 보낼 때마다 이전 답을 지우던 칸을, 질문·도구 호출·답이 위로 쌓이는
  대화로 바꿨다. 인스턴스별로 대화를 나누고 같은 탭 안에서는 새로고침해도 남는다(sessionStorage). 진행 중에는 보내기 버튼이
  중지로 바뀌고, 한글 조합 중 Enter로는 보내지 않는다. 자연어 진단 요청이 앞선 대화 최근 3턴을 `history`로 받아 참고 맥락으로만
  싣는다 — 앞 답을 근거로 인용하지 말고 도구로 다시 확인하라고 지시하며, 줄바꿈을 한 줄로 접어 새 질문 흉내를 막는다.
  하단 안내 문구와 인스턴스 목록의 "총 N대 — " 접두어를 뺐다.
- **`dbtower.aiops` 설정**: `alert-triggers.reply-channel`을 `channels.default`·`channels.by-team`으로 대체했다. 릴리즈 전
  변경이라 호환 처리는 없다. 게이지 갱신 주기는 `metrics-refresh-ms`(기본 15000)로 맞춘다.

### Fixed

- **워크벤치 인스턴스 목록이 한 건의 계정 복호화 실패로 통째로 비던 문제**(174절): 목록은 계정의 목적·아이디·수정 시각만 쓰는데
  엔티티를 불러와 비밀번호 칸까지 복호화했다. 저장할 때와 다른 암호화 키로 앱이 뜨면 한 인스턴스 때문에 목록 전체가 409로
  실패했다. 목록은 비밀번호 칸을 읽지 않는 조회로 바꾸고, 실제로 계정을 쓰는 순간의 복호화 실패는 "관리 화면에서 이 인스턴스의
  계정을 다시 등록하세요"라는 문장으로 돌려준다(원문 GCM 예외를 화면에 보이지 않는다).

## [1.3.1] - 2026-09-14

v1.3.0 공개 뒤 실제 MySQL·PostgreSQL 값과 승인 변경 흐름을 다시 읽고 촬영하며 발견한 표시·보안 결함을
고친 패치 릴리즈다. 현재 소개 화면과 7장면 데모 GIF도 같은 실행 결과로 다시 만들었다.

### Changed

- Top Query·시점 비교·Slow Query·이상·백분위·세션 표의 SQL에 안전한 경량 구문 강조를 적용했다.
  밝은 표 전용 색은 WCAG AA 대비 4.5:1 이상을 계산하고 브라우저에서 실제 색과 XSS 비실행을 검증했다.
- 현재 README·스크린샷 갤러리·발표의 관제 대표 화면과 승인→실행→전후 비교 7장면 GIF를 실제
  PostgreSQL 대상으로 다시 촬영했다. 과거 결함 증거 이미지는 검증 기록에 그대로 보존한다.

### Fixed

- 누적 통계가 ResultSet을 읽는 사이 변하면 MySQL Top Query의 Load가 내림차순이 아니게 보이던 문제를,
  한 번 읽은 동일 값으로 다시 정렬해 고쳤다.
- 딥링크 자동 비교와 사용자의 재조회가 겹칠 때 늦게 끝난 실패 응답이 성공 표 위를 덮던 경쟁 상태를
  마지막 요청만 반영하도록 고쳤다.

### Security

- PostgreSQL `pg_stat_statements`가 `CREATE ROLE ... PASSWORD '실값'` 같은 유틸리티 SQL을 원문으로
  제공할 수 있던 경로를 막았다. API 응답과 새 스냅샷 저장 시 리터럴을 가리고, 도입 전 저장된 행도
  조회 경계에서 다시 마스킹한다.

## [1.3.0] - 2026-09-14

두 갈래가 들어 있다. 하나는 HA 관측을 관제 plane의 경계 안에서 완성한 것이고(123절), 다른 하나는
**거버넌스 SQL 워크벤치**로 제품의 성격을 넓힌 것이다(126~166절) — 보기만 하던 관제탑에 "변경을
안전하게 실행하는 경로"를 붙였다. 관통 원칙은 그대로다: 관리 플랫폼은 대상 DB에 임의 변경을 하지
않고, **승인된 티켓만** 변경 계정으로 실행한다(행 사본·영향 행 수 대조·되돌리기 경로와 함께).

전부 라이브 재현·검증(docs/VERIFICATION.md 166개 절). 테스트 897건, 모듈 16개.

### Added

- **거버넌스 SQL 워크벤치**(128~132절): 분리된 조회 계정과 읽기 전용 트랜잭션으로 자유 SQL 조회,
  TOI식 워크시트와 실행 도구가 없는 격리 LLM의 SQL 제안(실행 결과로 채점해 정확도 12/12),
  승인된 티켓만 실행하는 변경 경로(사본 행 수 = 영향 행 수일 때만 커밋), 행·구조·계획·인스턴스 간
  전후 비교 4종. 5기종 전부 라이브에서 변경을 실행하고 원래 상태로 되돌렸다(157절에서 Oracle까지).
- **한 플랫폼, 사람별 입구**(135·136절): 역할 5개(관제·요청자·승인자·운영자·관리자). 승인자와
  운영자는 서로의 일을 하지 못한다. 화면은 `/api/me`의 능력으로 분기하고 역할마다 첫 화면이 다르다.
- **실시간 세션 관제**(140·144절): 대상별 채널 하나가 주기마다 한 번 조회해 SSE로 나누는 허브.
  10명이 30초간 볼 때 대상 조회 150 -> 14회, 전원 떠나면 0. 앱 노드가 여럿이면 조회권을 쥔 노드만
  조회하고 프레임을 교환한다(두 노드 30 -> 15회).
- **AI 응답 스트리밍 5경로**(141·143·146절): 워크벤치 제안·자연어 진단·쿼리 상세 분석·변경 요청
  소견·인시던트 리포트. 흘린 조각은 표시용이고 저장·분류는 완성본으로 한다.
- **활성 세션 샘플링(ASH)**(124·125절): 1초 해상도 폴링과 자체 저장. 세운 가설을 실측이 반증했다.
- **스키마·구조 심화**(150·151·153·156절): 스키마 트리(루트 -> 테이블/뷰 -> 열·인덱스), 테이블
  상세의 기본키·외래키(나가는 것·들어오는 것), 구조 스냅샷의 외래키·CHECK·트리거 비교.
- **쿼리 안티패턴 신호 5기종**(158절): 느림의 크기가 아니라 성질(인덱스 없이 훑음·디스크로 넘침·
  반환 행당 읽은 양)을 통계 뷰에서 읽는다. 없는 축은 0이 아니라 미확보로 표기한다.
- **MCP 워크벤치 도구**(131절): 요청·조회 도구만 열고 실행 도구는 두지 않는다.
- **5기종 자원 압박 신호**(162절): CPU 사용률을 인스턴스 지표처럼 오인하지 않고, DB가 직접
  제공하는 동시 실행량과 한도를 공통 축으로 수집한다. 한도를 모르면 감점하지 않는다.

### Changed

- **관제와 워크벤치를 한 페이지의 두 모드로**(149절): 상단 전환, 관제 쪽에도 늘 보이는 AI 칸.
  쿼리 넘김이 새 탭 대신 같은 페이지로 바뀌어 문서 로드가 사라졌다.
- **화면 재질·레이아웃**(138·139·147·159절): Liquid Glass는 뜨는 계층에만, 표·카드는 불투명.
  글자 대비는 계산으로 확인한다(보조 글자 3.77 -> 4.97:1). 워크벤치 배치 재구성, 티켓 상세를 카드로 분리.
- **현재 화면과 문서 원본 갱신**(163·165절): 통합 화면 이후 대표 이미지를 다시 선별하고 Mermaid
  원본·SVG, 16개 Modulith 문서, 역할별 수동 테스트와 문서 링크를 현재 구현에 맞췄다.
- **규약 검사 확장**(127·154절): MySQL 모니터 권한 기준선(오퍼레이터가 읽는 테이블 = init GRANT),
  원시 NUL 바이트 금지.

### Fixed

- **연결만 받고 말이 없는 대상 하나가 폴러 전체를 멈추던 것**(134절): 25초 초과 -> 약 2초.
- **AI를 기다리며 플랫폼 DB 커넥션을 쥐고 있던 것**(148절): 흘려 받기 4건 동안 active 4 -> 0.
  같은 회차에서 멈추거나 stderr가 찬 CLI 자식이 끝나지 않던 것도 수정(20초 초과 -> 0.11·2.01초).
- **인덱스 제안·쿼리 분석이 정규화 텍스트($1·$2)에서 502이던 것**(137·142절): GENERIC_PLAN 경로로 해소.
- **외래키만 바꾼 실행이 "차이 없음"으로 남던 것**(153절): 구조 스냅샷이 제약조건을 담지 않아서였다.
- **MongoDB가 계획을 뜰 수 없는 식별자로 대상 DB에 조회를 보내던 것**(160절): `queryStats`의
  대체 식별자(`op:ns`)를 계획 조회 전에 걸러낸다. 라이브 상위 그룹 5개 중 4개가 그 대상이었다.
- **화면이 스스로 받던 403과 팝업 오류**(161·162절): 역할에 없는 API 호출을 멈추고 `alert()` 5곳을
  실패한 화면의 상태 메시지로 옮겼다.
- **세션·숫자 표시 불변식**(162·165절): PostgreSQL의 음수 세션 경과와 MySQL 서버 데몬 혼입을
  실제 5기종 값 검사로 잡았다. 비유한 수와 음수 바이트는 화면에 `NaN`·`Infinity`로 노출하지 않는다.
- **SQL Server 2022 Apple Silicon 검증 경로**(164절): 지원 종료된 Azure SQL Edge 대신 별도 Rosetta
  Colima 프로필에서 실제 SQL Server 2022를 띄워 변경·되돌리기와 조회 경계를 포함한 5기종 검증을 닫았다.

### Security

- **자연어 진단의 팀 범위 우회**(126절): 루프 안 도구 호출이 서비스 토큰으로 돌아 팀 범위를 벗어나던
  것을 대상 고정 가드로 막고, 질문·도구 호출을 실제 주체로 감사에 남긴다.
- **MCP 요청자 신원**(132절): 채널이 호출자를 서비스 토큰으로 바꿔 부르면 자기 승인이 가능했다(200 -> 409).
- **세션 쿼리 리터럴 가림**(144절)과 **스트림 오류 원문 차단**(148절, CWE-209): 화면·AI로 나가는
  경로에서 실값과 예외 원문을 지우고 errorId만 남긴다.

---

아래는 123절(HA 관측) 회차의 기록이다.

### Added (HA 관측 회차)
- **HA 관측 — 역할 변경(failover)·split-brain 감지**(OpsAlertDetector): 인스턴스 역할을 폴 사이에
  추적해 STANDBY↔쓰기가능(WRITABLE)으로 뒤집히면 failover 신호로, 같은 cluster 라벨에 쓰기 가능
  노드가 둘 이상이면 split-brain으로 알린다. 대상에 쓰기 없이 `replicationState()`의 역할만 읽는다.
  실제 PG 페일오버로 라이브 검증하다 설계 결함(PG는 복제본 없는 primary를 STANDALONE으로 보고 →
  PRIMARY 개수로 세면 split-brain을 놓침)을 잡아 쓰기가능 모델로 수정(123.17).
- **대상별 운영 종합(overview) — `GET /api/instances/{id}/overview`**: 정체(이름·기종·환경·클러스터)
  + 헬스 스코어 + 복제(역할·지연·**RPO 노출**) + 백업 신선도를 한 객체로 모은다(DBRE의 "관측을 대상
  단위에 귀속"). score 모듈에 뒀다(이미 집계 허브라 순환 없이 operator만 추가). 읽기 전용 집계 —
  조치는 실행하지 않는다. 프론트 SPA 최상단에 종합 카드로 연결(123.20).
- **동기 내구성 정직 표기 — "설정된 동기 vs 지금 실제"를 SQL 4기종에서**: PostgreSQL
  `synchronous_standby_names` 설정인데 sync 스탠바이 0(123.18), MySQL 반동기가 타임아웃으로 async
  폴백(`Rpl_semi_sync_source_status=OFF`, 123.19), Oracle `protection_mode != protection_level`
  (MAX AVAILABILITY인데 RESYNCHRONIZATION 저하, 123.21)을 각각 `UNAVAILABLE`로 강등. MSSQL은
  이미 NOT SYNCHRONIZED 강등(123.1). MongoDB는 write concern 모델이라 과반 상실이 이미 잡힘.

### Fixed (HA 관측 회차)
- **Oracle 복제 지연 MEASURED가 구조적으로 불가능했던 결함**: apply lag 쿼리의 `value != ''`가
  Oracle에선 `value != NULL`(빈 문자열=NULL) → 항상 UNKNOWN → 정상값이어도 0행 → 늘 UNAVAILABLE.
  `value IS NOT NULL` 하나로 충분. 실제 Data Guard 앞에 세워야만 드러난 침묵(123.15).

## [1.2.0] - 2026-07-19

DBTower를 실제 운영 도구로 밀어붙인 릴리즈. 현업 DBA의 병목이 남는 다섯 지점(설정 드리프트·스키마 변경 리뷰 게이트·인덱스 사용 통계·인시던트·월간 리포트)을 기능으로 끊고, 웹 콘솔을 좌측 사이드바+모니터링 서브내비 구조로 전면 개편했으며, lakehouse 장기 분석계와 양방향 루프를 닫았다. 모듈 15개, MCP 도구 16종, 테스트 515건 CI, VERIFICATION 117개 절.

### Added
- 운영 병목 다섯 곳 끊기(B1~B5): 설정 드리프트 이력(파라미터 diff의 공간축에 시간축 — 거울 테이블+변경 로그로 무변경 주기엔 스냅샷 한 줄, work_mem 변경 감지해 카드), 스키마 변경 리뷰 게이트(배포 전 DDL/대량 DML을 JSqlParser 구문 트리로 판정 — 락 위험·DEFAULT 없는 NOT NULL·DROP·WHERE 없는 대량 변경, 실제 행수로 락 확정 + AI 1차 소견 + ADMIN 승인·자동 감사, 실행은 안 하고 gh-ost 경로만 안내), 인덱스 사용 통계 주기 영속(FinOps 미사용 인덱스 관측기간 라벨), 인시던트 리포트(장애 구간 시점비교·설정변경·플랜플립·대기·가용성 재구성 + AI 요약), 월간 점검 리포트(@monthly 자동 발행). 신규 모듈 review, 공개 파사드 score·finops로 Modulith 경계를 순환 없이 유지
- 웹 콘솔 전면 개편(의존성 0·빌드체인 0 유지): 좌측 사이드바(검색·필터 구동 인스턴스 선택기 — 초기 빈 리스트, 매칭분만 렌더해 수백~수천 대 확장) + 함대 개요 행 + 모니터링 카테고리 서브내비(성능·진단·거버넌스·백업/리포트·비용/인프라). 5기종 공식 브랜드 로고(devicon SVG 스프라이트 <use>), 모니터링 차트 호버 툴팁(커서 최근점 정확 수치·시각), 쿼리 상세를 클릭 행 바로 아래 인라인 확장, MySQL 클래식 EXPLAIN 실행계획 표, SQL·실행계획 JSON 구문 색상강조(경량 토크나이저), 커스텀 드롭다운·전역 커스텀 툴팁, 전체 반응형 레이아웃
- Metric 시계열 확장: MySQL Query Activity(Queries·Prepared Statement Calls·Slow Query·TABLE FULL SCAN)·Row Operation 그룹 렌더
- 인스턴스 조직 태그(V30): 환경/리전/클러스터 라벨 + 사이드바 5차원 필터(AWS RDS 개념을 이기종에 라벨로 일반화)
- volumeStat() 공급(임계 원천 ②): MSSQL 볼륨 총량/여유·Oracle autoextend 상한을
  size_snapshot에 스탬프 — lakehouse 용량 D-day가 seed 없이도 잡힌다(volume_reported).
- 자연어 서빙 MCP 도구 2종: `lakehouse_query`(장기 마트 SELECT — 가드·행 상한)·
  `lakehouse_card_create`(Metabase 카드 생성, "DBTower AI" 컬렉션 격리) — Metabase API
  경유로 DuckLake 마트를 에이전트에 연다(셀프호스트에 없는 Metabot의 대체 경로).
- 오브젝트 크기 주기 영속: V26 `size_snapshot` + 6시간 수집·7일 보존 잡 — lakehouse
  용량 예측(장기 D-day)의 원료. 단기 디스크 ETA(78절)와 지평 분리.
- 대기 이벤트 주기 영속(D1): V25 `wait_event_snapshot` + 5분 수집·7일 보존 잡 —
  lakehouse 장기 추세 분석의 공급원(첫 사이클 6인스턴스 134행 실측).
- 장기 베이스라인 수신·병합(D8): V24 `baseline_longterm`(lakehouse 되쓰기 수신) +
  BaselineService 가중 병합 — 요일x시간대 장기 통계를 QPS 축에 결합해 주간 계절성
  오탐을 줄인다(빈 테이블이면 현행 그대로, 회귀 0). `dbtower.baseline.longterm-enabled`.
- plan_snapshot 보존 시간 하한(D2): 카운트 스윕에 `retention-min-age-hours`(기본 48h)
  병행 — lakehouse D+1 하루창 추출 전 유실 차단.

### Added
- 커넥션 온디맨드(Phase 4) — minimumIdle 0 + idleTimeout 하한 가드(수집 주기+30s) + 장기 미사용 풀
  LRU 정리: 격리·저빈도 대상의 유휴 커넥션이 0으로 수렴(이전엔 대상마다 1개 영구 점유).
  SLO 헬스 폴러가 격리를 무시하고 핑하던 구멍도 해소
- query_snapshot 월별 RANGE 파티셔닝(V18) — 보존 정리가 벌크 DELETE(200만 행 1.9초+블로트)에서
  월 파티션 DROP(12.8ms·블로트 0)으로. 보존 잡이 파티션 선생성·DROP·걸친 구간 DELETE까지 관리,
  비파티션 환경은 DELETE 폴백(같은 계약 유지)
- 수집 샤딩(Phase 4) — dbtower.snapshot.shards=N: 샤드별 락을 각각 시도해 노드 여럿이면 수집을
  나눠 들고(자연 분산), 한 노드면 전 샤드 인수(무설정 페일오버). 기본 1은 현행과 완전 동일
- 로그인 잠금 카운터 메타 DB 이관(V17) — 인메모리(노드별 독립·재시작 소실)의 LB 뒤 임계 N배
  구멍 해소: 노드를 오간 실패도 하나의 임계로 잠긴다
- 서버 공유 인지(Phase 4 호스트 그룹핑) — 같은 host:port에 등록된 DB들의 서버 전역 신호(유휴
  트랜잭션·복제·데드락)를 그룹당 1회만 감지·경보(공유 인스턴스 명시), 호스트 스코프 Advisor(디스크
  예측)는 스윕에서 호스트당 1회(나머지 SHARED 표기), 콘솔 카드에 "서버 공유 ×N" 배지.
  위험 귀속(헬스 스코어·온디맨드 점검)은 dedup하지 않는다
- 디스크 포화 예측(Phase 5) — node_exporter 선형 추세로 "며칠 뒤 차는가"(ETA≤3일 치명/≤14일 경고,
  추세 없어도 여유<10% 경고). 인스턴스-노드 매핑 node_filter(V16, label="value" 셀렉터만 허용해
  PromQL 주입 방지), Prometheus 미설정 시 조용히 스킵. node-exporter compose를 정석(rootfs 마운트)으로
- LICENSE(Apache-2.0) + NOTICE — 번들 JDBC 드라이버(MySQL GPLv2+FOSS Exception·Oracle 독점 등)·
  이미지 번들 CLI 재배포 고지. 이전엔 라이선스 부재로 법적 재사용 불가였음
- DB팀 문의를 Discord 리치 embed로 — 필드 구조화(요청자·쿼리 sql 코드블록·실행계획·규칙·AI 분석),
  한도(256/1024/25) 경계 절단, Slack·미설정 텍스트 폴백, 레이트리밋 윈도우 공유
- 프로젝트 아이콘·파비콘 세트, 진단 흐름 다이어그램(식별→분석→문의) README 추가
- MCP OAuth 2.1 인가 서버(V20) — MCP 클라이언트가 브라우저 로그인으로 토큰 자동 발급.
  RFC 9728/8414 discovery + RFC 7591 동적 클라이언트 등록 + PKCE(S256) + refresh 회전, 기존
  로그인·유저 재사용. /mcp 전용 stateless 체인으로 401+WWW-Authenticate 자동 discovery

### Security
- 백업 산출물 저장 암호화(3-2-1-1-0), Vault 동적 자격증명으로 정적 비밀번호 유출 창 축소,
  Vault creds 경로를 database/creds/ 마운트로 봉인(임의 시크릿 접근 차단), Discord 인바운드
  Ed25519 서명·채널/유저 화이트리스트(기본 거부)
- 암호화 fail-closed를 배포 프로필 전체로 — 셀프호스트(docker 프로필)에서 DBTOWER_ENCRYPTION_KEY가
  없으면 기동을 거부한다(이전엔 prod 프로필만 막아 docker 경로가 평문 저장으로 뚫려 있었음, CWE-312).
  compose 수준 `${DBTOWER_ENCRYPTION_KEY:?}` 이중 방어
- 커밋됐던 바이너리 H2 DB(data/dbhub.mv.db, USERS/PASSWORD 테이블) 저장소에서 제거 + data/ gitignore
- 로그인 브루트포스 방어 — 계정별 연속 실패 시 잠금(기본 10회/15분), 잠긴 계정은 인증 앞에서 차단.
  로그인 화면에 남은 시간 표시
- 리버스 프록시 HTTPS 종단 지원 — forward-headers-strategy + 세션·CSRF 쿠키 Secure 토글(DBTOWER_COOKIE_SECURE)
- Prometheus 스크레이프 경로(/actuator/prometheus) 선택적 토큰 보호(DBTOWER_METRICS_TOKEN) — 미설정 시 현행+기동 WARN
- API 토큰 재시작 생존 — 미설정 시 매 기동 랜덤 재생성되던 것을 메타DB(platform_setting, V11)에 저장해
  재시작에도 동일 유지(MCP 연동이 재시작마다 깨지던 문제 해소)

### Added
- 테이블 상세 정보(5기종) — CREATE TABLE 전문·기본 통계(엔진·행수·데이터/인덱스 크기·평균 행 길이·
  생성 시각)·인덱스 정보(타입·카디널리티)를 상세 패널 아코디언과 API로. DDL은 NATIVE(MySQL SHOW CREATE
  TABLE·Oracle DBMS_METADATA)/RECONSTRUCTED(PG·MSSQL 카탈로그 재구성)/UNSUPPORTED로 출처를 정직 구분,
  미확보 카디널리티는 표시하지 않음. 식별자 주입 방어. POST /api/instances/{id}/table-detail.
  PG 재구성은 pg_get_constraintdef·pg_get_indexdef(엔진 자체 함수)로 FK·CHECK까지 정확히 담고,
  담지 못하는 트리거·파티션은 실제로 있을 때만 명시 — 배지에서 "(근사)"를 뗌
- 문의에 참조 테이블 구조 첨부 — 쿼리의 FROM·JOIN 테이블(조인 대상 포함)의 컬럼·인덱스·대략 행수를
  인덱스 중심으로 요약해 Discord/Slack 문의와 사이트 상세 패널에 표시(진단 핵심 재료). 존재하지 않는
  참조는 notFound로 정직 표기. POST /api/instances/{id}/referenced-schema
- 플랫폼 메타 DB 자기 백업 — pg_dump로 관제탑 자신의 상태 저장소를 주기 백업(로컬 + 원격 meta/),
  대상 DB만 백업하던 사각을 메움. PG 아니면 조용히 스킵
- CONTRIBUTING·CODE_OF_CONDUCT·이슈/PR 템플릿, README 시스템 요구사항 절, 스프링 부트 네이티브 롤링 파일 로깅

### Changed
- AI 분석 규칙 파일을 이미지에 번들(.dockerignore 예외 + Dockerfile COPY) — 이전엔 docs 제외로
  셀프호스트 이미지에서 AI 판정이 빈 프롬프트였음. compose에 ANTHROPIC_API_KEY 배선
- Spring Modulith internal 캡슐화 — 14개 모듈 전부 루트에는 공개 API(서비스·record)만 남기고
  구현을 internal/{web,domain,persistence,job}로 은닉. operator는 record 21종을 model/(@NamedInterface)로.
  타 모듈의 repository·entity 직접 참조 제거, ModularityTests가 경계 강제. docs/modules Documenter 재생성
- 문서 정리 — 중복 "ROADMAP 2.md"·손상된 PHASE-D-PLAN.md·보일러플레이트 HELP.md 삭제,
  AGENTS.md 모듈 트리 14개·Lombok 실제 정책 반영, README 로그인 안내 정합, DESIGN/HARDENING/
  deepening-spec에 완료 배너, ROADMAP에 프로덕션 로드맵(Phase 0~5)·셀프호스트 준비도 감사 추가
- 브랜딩 — 콘솔·로그인 헤더를 파비콘(favicon.svg) 로고 + "DBTower"로 통일하고 중복된 텍스트 "DB"
  마크 제거. 로그인 화면(미인증)에서도 파비콘이 뜨도록 SecurityConfig permitAll에 파비콘 자산 추가

### Added
- 팀 스코핑(LBAC, Phase 3) — 사용자에 팀 라벨(V14)을 달면 그 팀 인스턴스 + 라벨 없는 전역만 보인다.
  강제는 단일 경계(RegistryService.findAll/findById), 스코프는 로그인 authority로 부여(폴러·ADMIN은
  전역). 스코프 밖 단건은 미등록과 같은 404(존재 노출 방지). ADMIN이 PATCH로 팀 지정
- 공유 세션(spring-session-jdbc, V15) — 세션을 메타 DB에 저장해 앱 재시작·다중 노드에서 로그인 생존
- 로그 백업 5기종 전부 실동작 + PITR(Phase 2 완결) — MySQL binlog(FLUSH 경계), PostgreSQL WAL
  세그먼트(pg_switch_wal — wal_level·권한 게이트), MongoDB oplog(데모를 단일노드 replSet으로 전환),
  Oracle 아카이브 로그(ARCHIVELOG 게이트 + V$ARCHIVED_LOG 수집, PDB에선 ARCHIVE LOG CURRENT가
  CDB 수준 작업이라 best-effort — ORA-65040 실측 교훈), SQL Server BACKUP LOG. 백업 이력에 타입(V13)과 UNSUPPORTED 상태 신설("못 하는 것"≠"깨진 것").
  GET /api/instances/{id}/pitr-window — 복원 가능 창(마지막 FULL~마지막 LOG) + 기종별 복원 명령
  문안 생성(MySQL --stop-datetime·MSSQL STOPAT — 실행은 사람). MSSQL 실제 시점 복원 e2e 실증
- 데이터 마스킹 — 외부(웹훅·MCP 응답)로 나가는 SQL의 리터럴만 ?로 가림(식별자·구조 보존, 정규화
  텍스트엔 멱등). 회귀 알림·문의·MCP 에코 4곳 배선, AI 프롬프트는 mask-ai-prompt(기본 false) 토글
- 통계 수집 건강 Advisor — MySQL digest 포화율(80%)·Performance_schema_digest_lost(신규 쿼리 감지
  무력화)·Prepared Statement 익명 부하(digest 미집계 실측 근거), PG pg_stat_statements evict(dealloc)
  감시. 조치는 명령 안내까지만(대상 DB 변경 금지)
- 인스턴스 팀 라벨·콘솔 딥링크(V12) — team_label(Phase 3 LBAC와 컬럼 공유)·console_url(http/https만
  허용, 스킴 주입 차단). 카드 배지·회귀 알림·문의 embed에 담당 표기
- 알림 → 진단 딥링크 — dbtower.base-url 설정 시 회귀 알림에 콘솔 링크(인스턴스 선택+자연어 진단
  질문 프리필). metrics MCP 도구(14종째) — AI 진단이 CPU·Connections를 스스로 확인
- 모니터링 지표 통합 — Monitoring 탭에 CPU(%)·Connections 그래프 내장(Prometheus HTTP API 직접 조회,
  insight/internal PrometheusClient + GET /api/instances/{id}/metrics). CPU는 node_exporter 호스트 수준,
  Connections는 기종 exporter(MySQL threads_connected·PG numbackends). 미설정·미수집·미지원은 사유를
  그대로 표기(기능 게이트 — Prometheus가 없어도 콘솔은 정상). compose에 node-exporter 추가.
  시점 비교 드래그 그래프에 QPS ↔ CPU% 토글 — CPU 그래프 위에서 조회·비교 구간을 드래그로 선택

### Changed
- 문제 쿼리 식별 표 컬럼 보강 — Top Query 기본뷰에 Call/sec(스냅샷 차분)·평균 Latency(ms)·Row Examined(Avg),
  Slow Query에 User@host·Lock(ms)·Rows_sent(MySQL slow_log), MongoDB Slow Query에 Plan(IXSCAN/COLLSCAN,
  system.profile planSummary) 컬럼 추가. 미확보 필드는 "—"로 정직 표기(Call/sec는 스냅샷 이력 없으면 "—")
- 화면 패리티 마감 3건 — 비교뷰에 Load(시간 점유율) 증감 첫 컬럼 + Load 내림차순 정렬,
  Monitoring Metric 카드에 Query Activity(QPS) 그래프 병치, MongoDB Top Query(집계)에 Plan 배지
  (QueryStat.plan — Mongo profiler만 채움, 값이 있을 때만 컬럼 렌더)

### Fixed
- 웹 콘솔 타임존 스큐 — 앱 JVM은 UTC 고정(C-6)인데 프론트가 브라우저 벽시계(KST 등)를 그대로 보내
  활동 그래프·비교 조회가 9시간 미래의 빈 구간을 조회하던 것을 수정. 보낼 땐 toISOString(UTC),
  받을 땐 Z를 부여해 진짜 instant로 — 차트 축·드래그 선택·입력 표시는 브라우저 로컬로 일관
- 웹 콘솔 전체 백화 — 테이블 상세 추가 시 `const fmtBytes`가 기존 선언과 중복돼 app.js가 SyntaxError로
  파싱 중단, SPA가 아무것도 렌더하지 못하던 것을 수정(중복 제거, 음수 크기 "—" 표기는 헬퍼로 보존).
  Java 단위·curl API는 프론트 파싱을 안 거쳐 놓쳤고 브라우저 실물 확인이 잡음

### 업그레이드 노트 (암호화 fail-closed)
- 기존에 DBTOWER_ENCRYPTION_KEY 없이(평문 저장) docker로 운영하던 경우, 이번 버전부터 기동이 거부된다.
  절차: (1) `openssl rand -base64 32`로 키 생성해 .env에 설정 → (2) 기동 → (3) 기존 인스턴스는 평문으로
  저장돼 있으므로, 각 인스턴스를 웹 UI(또는 PUT /api/instances)에서 한 번 재저장하면 새 키로 암호화된다.
  (평문 행은 하위호환으로 계속 읽히지만, 키 설정 후 재저장 전까지는 암호화되지 않은 상태)

## [1.1.0] - 2026-07-07

v1.0.0으로 만들 만큼 만들었다 싶었는데, 실제로 쓰다 보니 필요한 게 계속 보였다. 심화 네 아크로
기존 기능을 다섯 기종에서 온전하게 다듬고, 그다음 만든 것을 스스로 감사해 하드닝했다. 상세 재현
기록은 [VERIFICATION.md](docs/VERIFICATION.md) 57~62절, 판단 근거는 [HARDENING-ROADMAP.md](docs/HARDENING-ROADMAP.md).

### Added
- **플랜 플립 감지 5기종화** — 실행계획 변경 감지가 PostgreSQL만 완전하던 것을 다섯 기종으로 확장.
  `DbmsOperator.planShapeForDigest`로 계획 획득 경로를 통일(MySQL `QUERY_SAMPLE_TEXT` 재EXPLAIN,
  SQL Server Query Store showplan, Oracle `v$sqlstats` plan_hash_value, MongoDB 프로파일러 명령
  재explain), `PlanShapes` 한 겹으로 JSON·XML·해시를 비교 가능한 형태로 정규화.
- **레이턴시 p95 source 등급 상향** — 라벨 신설 `NATIVE_WINDOWED`(히스토그램 두 스냅샷 차분 =
  최근 구간), `NATIVE_HISTOGRAM`(버킷 보간). MySQL 구간 p95, SQL Server Query Store 추정(ESTIMATED)
  해제, MongoDB `opLatencies` 히스토그램으로 프로파일러 무관 인스턴스 p95. Oracle은 원자료 부재로
  UNSUPPORTED 유지(정직성 대비군).
- **데드락 감지** — `recentDeadlocks`(SQL Server `system_health` XE, MySQL `SHOW ENGINE INNODB STATUS`),
  `deadlockCount`(PostgreSQL `pg_stat_database.deadlocks` 카운터 델타). `/api/instances/{id}/deadlocks`
  API + Monitoring 카드.
- **PG 복제 슬롯 감시** — 비활성 슬롯의 WAL 보존량 감시(디스크 고갈 사각) + 블로트/통계 노후 Advisor.
- **스케일 제어(Phase F)** — 수집 병렬화(워커 풀, ShedLock 노드 배타 유지), 스케줄러 풀 분리,
  알림 폭주 제어(분당 상한 + 초과분 묶음 요약), 인스턴스별 수집 격리 토글(`collectionEnabled`,
  `PATCH /api/instances/{id}/collection`), 헬스 스코어 노드별 캐시.
- **TLS 강제 접속** — 5기종 `useTls` 옵션(관리형 서비스 대응). 인증서 검증 우회 옵션은 의도적으로 없음.
- **백업 원격 보관** — S3 호환 오프사이트 업로드로 3-2-1 완성. 업로드 실패는 백업 실패로 취급하지 않음.

### Fixed
- **MySQL slowQueries sub-second 절삭** — `TIME_TO_SEC`가 정수를 반환해 1초 미만 쿼리가 0ms로
  보고되던 것을 `+ MICROSECOND(query_time)/1000` 보정으로 실측 ms 표기(예: 600.594ms).
- **MySQL 히스토그램 2^64-1 센티넬 오버플로** — `BUCKET_TIMER_HIGH`의 unsigned bigint 최댓값 센티넬을
  `getLong()`이 못 받던 것을 BigDecimal로 처리.
- **최소권한 계정 조용한 폴백** — 모니터 계정이 히스토그램 뷰 권한만 빠졌을 때 무음으로 누적값 폴백하던
  것을 경고 로그로 관측 가능화(+ `mysql-init` 권한 추가).
- **인스턴스 삭제 시 자원 누수** — 자식 테이블에 FK `ON DELETE CASCADE`(V10) + 삭제 이벤트로 인메모리
  상태(히스토그램 스냅샷·데드락 카운터·쿨다운·백오프) evict 배선.
- **커넥션 풀 경합발 허위 장애** — 인스턴스별 HikariCP 풀을 2→6으로 설정화(폴러 다수 동시 접근 시
  허위 백오프·"수집 정지" 경보 방지).
- **PG deadlockCount 형제 DB 사각** — `current_database()`만 보던 것을 클러스터 전체 SUM으로.
- **Oracle 통계 스키마 필터** — 모니터 계정 `CURRENT_SCHEMA`로 필터해 앱 SQL이 안 보이던 것을
  설정값(`dbtower.oracle.app-schema`) 기반으로. `plan_hash_value=0`(계획 미포착) 허위 플립 방지.
- **타임존 미고정** — `TimeZone.setDefault(UTC)` + `hibernate.jdbc.time_zone=UTC`로 노드 간 시각 일관성 확보.
- 스케줄러 시작 지터 상한, `Future.get` 예외 분리, `plan_snapshot` 보존 잡, 종료 가드 등.

### Security
- **XXE 방어 강화** — 데드락/showplan XML 파서 3곳에 `disallow-doctype-decl` +
  `FEATURE_SECURE_PROCESSING` 추가(CWE-611, 블라인드 SSRF 차단).
- **스택 쿼리 거부** — `requireSelect` 게이트가 `SELECT 1; DROP TABLE x` 같은 다중문을 거부(CWE-89).
- **암호화 fail-closed** — `prod` 프로필에서 암호화 키 미설정 시 기동 실패(평문 저장 방지, CWE-312).
- **에러 텍스트 마스킹** — 대상 DB 에러 원문 대신 일반 메시지 + errorId(CWE-209).
- **웹훅 멘션 인젝션 차단** — Discord `allowed_mentions: {parse: []}` + 제어문자 이스케이프.
- **SQL Server Azure 오탐 방지** — `EngineEdition=5`(Azure SQL DB)면 `system_health` 부재를 정직 처리.

## [1.0.1] - 2026-07-06

### Fixed
- **멀티아치 이미지** — v1.0.0의 amd64 단일 아치를 QEMU + `platforms: linux/amd64,linux/arm64`로
  멀티아치 게시(ARM 서버·Apple Silicon에서 에뮬레이션 없이 실행).

## [1.0.0] - 2026-07-06

첫 공개 릴리스. 이기종 DBMS 5기종(MySQL·PostgreSQL·SQL Server·Oracle·MongoDB)을 인터페이스 하나
(`DbmsOperator`) 뒤에서 등록·진단·백업·자율 감시하는 컨트롤 플레인.

### Added
- **5기종 지원** — 새 기종 = Operator 구현체 1개, 플랫폼 코드 0줄 수정(실측).
- **진단** — 쿼리 통계·시점 비교·회귀 감지·Wait Event·세션/블로킹·실제 실행 계획 심층 진단.
- **자율 진단** — 이상 감지(z-score)·Advisors·SLO/에러 버짓·헬스 스코어·자연어 진단(read-only MCP 도구 루프).
- **운영 안전** — 인증/인가(세션 + Bearer 토큰)·비밀번호 암호화(AES-256-GCM)·Flyway·보존 정책·
  ShedLock HA·감사 로그·백업 복원 검증(3값)·최소 권한.
- **프로비저닝 연동** — K8s(CloudNativePG)·Ansible·Terraform에서 생성 즉시 멱등 등록.
- **채널** — 웹 콘솔·MCP(stdio/HTTP)·웹훅.
- **셀프호스트 제품화** — 배터리 포함 컨테이너 이미지(백업 CLI 번들), 원커맨드 docker compose,
  태그 push가 곧 게시인 GHCR 파이프라인.

[Unreleased]: https://github.com/dj258255/dbtower/compare/v1.3.1...HEAD
[1.3.1]: https://github.com/dj258255/dbtower/releases/tag/v1.3.1
[1.3.0]: https://github.com/dj258255/dbtower/releases/tag/v1.3.0
[1.2.0]: https://github.com/dj258255/dbtower/releases/tag/v1.2.0
[1.1.0]: https://github.com/dj258255/dbtower/releases/tag/v1.1.0
[1.0.1]: https://github.com/dj258255/dbtower/releases/tag/v1.0.1
[1.0.0]: https://github.com/dj258255/dbtower/releases/tag/v1.0.0
