# 현재 화면 갤러리

README와 발표·포트폴리오에서 사용할 현재 화면만 모았습니다. `VERIFICATION.md`의 이전 캡처와
전후 비교 이미지는 당시 결함과 수정 근거이므로 덮어쓰지 않습니다. 화면을 바꾸면 이 문서의 대표
이미지를 먼저 다시 확인하고, 과거 증거는 절 번호와 함께 그대로 보존합니다.

## 관제와 워크벤치

![관제 모드 — 실제 MySQL Top Query의 값과 SQL 강조를 함께 검증](images/webui/165-unified-monitor-query-highlighted.jpg)

![워크벤치 모드 — 스키마, 편집기와 결과, AI 보조를 함께](images/webui/143-unified-workbench-mode.jpg)

## 스키마와 결과 탐색

![스키마 트리 — 테이블과 뷰, 기본키와 인덱스를 계층으로](images/webui/146-schema-tree-after.jpg)

![테이블 상세 — 기본키·외래키·참조 방향과 접힌 DDL](images/webui/148-table-detail-after.jpg)

![조회 결과 — 열 폭 조절과 행 상세](images/webui/149-grid-row-detail.jpg)

## 진단과 변경 거버넌스

![안티패턴 신호 — 값, 단위와 원천 지표를 함께 표시](images/webui/159-antipattern-signals.jpg)

![변경 티켓 — 요청 사유, 규칙 판정과 AI 소견을 분리](images/webui/160-ticket-detail-blocks.jpg)

![지원하지 않는 어드바이저 — 실행 전에 기종별 이유 표시](images/webui/161-advisor-unsupported.jpg)

## 운영 종합

![헬스 스코어 — 감점과 원천 지표를 신호별로 분해](images/webui/162-score-resource.jpg)

![SLO와 에러 버짓 — 값의 출처와 산식을 함께 표시](images/webui/163-slo-card.jpg)

SLO 화면의 `360,003.11ms`는 캡처에 함께 보이는 360초 부하 재현 SQL의 실제 통계입니다.
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
