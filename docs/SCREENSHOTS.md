# 현재 화면 갤러리

README와 발표·포트폴리오에서 사용할 현재 화면만 모았습니다. `VERIFICATION.md`의 이전 캡처와
전후 비교 이미지는 당시 결함과 수정 근거이므로 덮어쓰지 않습니다. 화면을 바꾸면 이 문서의 대표
이미지를 먼저 다시 확인하고, 과거 증거는 절 번호와 함께 그대로 보존합니다.

## 현재 화면 (UX 1차·글자 크기 기준 이후, 185절)

UX 1차(184절: 채운 면 버튼, 쿼리 상세 정리, 모니터링 탭 재배치)와 #61(글자 크기 네 단계, 조회 구간 한 줄) 뒤의 화면입니다.
로컬 개발 앱에 붙은 실제 PostgreSQL 16(live-postgres-team-b, sample DB)에서 2026-09-17 22:36~23:03(KST)에 찍었습니다.
표의 값은 촬영 전에 sample DB에 돌린 예시 조회(orders·customers)의 실제 통계입니다(185절에 명령과 함께 기록).

![관제 첫 화면 — 헬스 스코어, 백업 신선도, 조회 구간 한 줄, AI 어시스턴트](images/webui/188-console-first-screen.jpg)

![조회 구간 한 줄과 Top Query — 부하·호출/초·지연·돌려주거나 바꾼 행](images/webui/189-console-time-top-query.jpg)

![시점 비교 — 접기를 열어 비교 구간을 고르고, 증감과 신규 쿼리를 한 표에](images/webui/197-console-compare.jpg)

![쿼리 상세 — 쿼리 ID, 토글 다섯, 실행계획](images/webui/190-querydetail-plan.jpg)

![쿼리 상세 — 인덱스 제안의 조건 열 후보와 테이블 구조 표](images/webui/191-querydetail-schema.jpg)

![인덱스 제안 — 가상 인덱스로 Total Cost 60.74 -> 40.38](images/webui/192-querydetail-index-simulation.jpg)

![안티패턴 신호와 AI 분석 — 규칙 지적이 계획에 맞지 않는다고 근거와 함께 답한다](images/webui/193-querydetail-antipattern-ai.jpg)

![진단 탭 — AI 운영 작업을 행 아래로 펼치고 결론부터](images/webui/194-monitor-aiops-inline.jpg)

![관리 탭 — 사용자·역할과 역할별 할 수 있는 일](images/webui/195-monitor-admin-roles.jpg)

![워크벤치 — 스키마 트리, 워크시트 탭, 조회 결과, 버전 기록](images/webui/196-workbench-query-result.jpg)

아래는 이번에 다시 찍지 않았고 모양이 크게 바뀌지 않은 화면입니다(버튼 모양·글자 크기는 위 화면 기준).

![로그인 — 단색 하늘 배경, 그룹형 입력, 칸 안 화살표 버튼(172절)](images/webui/166-sky-login.jpg)

![로그인 — 휴대폰 폭의 오류 한 줄과 안내 상자, 가로 넘침 0(172절)](images/webui/167-sky-login-mobile-error.jpg)

![관제 AI 채팅 — 답 서식, 도구 이름, 데이터 보호 한 줄(177절)](images/webui/172-chat-conversation.png)

![관제 AI 채팅 — 대화 목록과 인라인 삭제(177절)](images/webui/173-chat-conversation-list.png)

![워크시트 요청 실패가 탭 줄에 그대로 보인다(180절)](images/webui/182-workbench-worksheet-error.png)

![MCP 연동 — 명령만 든 code, 접힌 제공 도구(179절)](images/webui/179-monitor-mcp-collapsed.png)

![등록된 인스턴스 0대, ADMIN — 등록 경로를 문장으로(181절)](images/webui/185-first-console-empty-admin.png)

![390px 관제 — 쿼리 상세가 스크롤 상자의 보이는 폭 안에(181절)](images/webui/186-narrow-console-390.png)

![390px 워크벤치(181절)](images/webui/187-narrow-workbench-390.png)

## v1.4.0 게시 당시 화면

UX 1차 전에 찍은 화면입니다. 버튼이 유리 재질이고 쿼리 상세·모니터링 탭 배치가 지금과 다릅니다. 전후 비교용으로만 남깁니다.

![v1.4.0 관제 — 하늘 원톤(175절)](images/webui/169-sky-monitor-after.jpg)

![v1.4.0 워크벤치 — 하늘 원톤과 편집기 위 워크시트 탭(175·180절)](images/webui/171-sky-workbench-after.jpg)

![v1.4.0 쿼리 상세 — 버튼 8개를 토글 5개와 더보기로(178절)](images/webui/175-querydetail-toggled.png)

![v1.4.0 쿼리 상세 — 표 SQL의 강조 툴팁(178절)](images/webui/176-querydetail-sql-tip.png)

![v1.4.0 쿼리 상세 — 더보기로 연 심층 진단의 실행 버튼(178절)](images/webui/177-querydetail-more-menu.png)

![v1.4.0 워크벤치 전체(180절)](images/webui/181-workbench-full.png)

![v1.4.0 사용자·역할 — 적용 전 확인과 자기 강등 경고(179절)](images/webui/178-monitor-users-role.png)

## v1.3 화면 (보라 원톤)

아래는 175절에서 하늘 원톤으로 바꾸기 전(v1.3.0·v1.3.1)에 찍은 화면입니다. v1.3 소개와
전후 비교용으로만 남기며, 현재 화면으로 소개하지 않습니다.

![v1.3 관제 모드 — 보라 원톤의 Top Query](images/webui/165-unified-monitor-query-highlighted.jpg)

![v1.3 워크벤치 모드 — 보라 원톤](images/webui/143-unified-workbench-mode.jpg)

![v1.3 스키마 트리 — 테이블과 뷰, 기본키와 인덱스를 계층으로](images/webui/146-schema-tree-after.jpg)

![v1.3 테이블 상세 — 기본키·외래키·참조 방향과 접힌 DDL](images/webui/148-table-detail-after.jpg)

![v1.3 조회 결과 — 열 폭 조절과 행 상세](images/webui/149-grid-row-detail.jpg)

![v1.3 안티패턴 신호 — 값, 단위와 원천 지표를 함께 표시](images/webui/159-antipattern-signals.jpg)

![v1.3 변경 티켓 — 요청 사유, 규칙 판정과 AI 소견을 분리](images/webui/160-ticket-detail-blocks.jpg)

![v1.3 지원하지 않는 어드바이저 — 실행 전에 기종별 이유 표시](images/webui/161-advisor-unsupported.jpg)

![v1.3 헬스 스코어 — 감점과 원천 지표를 신호별로 분해](images/webui/162-score-resource.jpg)

![v1.3 SLO와 에러 버짓 — 값의 출처와 산식을 함께 표시](images/webui/163-slo-card.jpg)

v1.3 SLO 화면의 `360,003.11ms`는 캡처에 함께 보이는 360초 부하 재현 SQL의 실제 통계입니다.
PostgreSQL이 제공하는 평균과 표준편차로 계산한 값이라 `추정`으로 표시하며, 실측 백분위인 것처럼
보이지 않습니다.

`164-session-table.jpg`는 PostgreSQL 세션 경과가 `-7.84ms`로 보이던 결함을 발견한 증거입니다.
현재 화면으로 소개하지 않고 [VERIFICATION 162절](VERIFICATION.md)에만 보존합니다. 현재 구현은
`clock_timestamp()`를 사용하고 0으로 바닥쳐 음수 경과를 내보내지 않습니다.

## 갱신 원칙

- 현재 화면 소개에는 이 문서에 선별한 캡처만 사용합니다.
- 전후 비교와 결함 발견 캡처는 파일을 교체하지 않고 `VERIFICATION.md`에 남깁니다.
- 캡처를 추가할 때 숫자의 단위, 범위, 시각, 출처 라벨을 함께 확인합니다.
- 이미지 파일은 디코딩과 가로·세로 크기를 검사하고, Markdown의 로컬 링크 누락도 확인합니다.
