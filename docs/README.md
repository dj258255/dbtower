# DBTower 문서 안내

README에는 제품 개요와 실행에 필요한 최소 정보만 둡니다. 이 문서는 **누가 무엇을 하러 왔는지**에 따라
읽을 문서를 고르는 지도입니다.

## 누가 읽나

### 운영자 — 설치하고 대상 DB를 붙이는 사람

| 순서 | 문서 | 여기서 얻는 것 |
|---|---|---|
| 1 | [루트 README 빠른 시작](../README.md) | 이미지 실행과 필수 환경변수(메타 DB 비밀번호·암호화 키), 버전 고정 |
| 2 | [least-privilege.md](least-privilege.md) | DBMS별 모니터링·조회·변경 계정 최소 권한 |
| 3 | [operations.md](operations.md) | 수집·통계·알림, 정식 이미지 배포·업그레이드 시 주의점 |
| 4 | 인프라 자동 등록 | [Kubernetes](../infra/k8s/README.md) · [Terraform](../infra/terraform/README.md) · [Ansible](../infra/ansible/README.md) |
| 5 | [API.md](API.md) | 토큰 인증, 주요 REST·MCP 진입점 |

### 온콜 — 경보를 받고 원인을 찾는 사람

| 순서 | 문서 | 여기서 얻는 것 |
|---|---|---|
| 1 | [DEMO-5MIN.md](DEMO-5MIN.md) | Top Query -> 쿼리 상세(실행계획·AI 분석) -> 인덱스 제안 -> 변경 티켓 승인·실행까지 실제로 누르는 순서 |
| 2 | [ai-analysis-rules.md](ai-analysis-rules.md) | AI 1차 분석이 무엇을 기준으로 판정하는지, 기종별 예외 |
| 3 | [AI-OPERATIONS-AUTOMATION.md](AI-OPERATIONS-AUTOMATION.md) | Slack·경보가 만든 AI 작업이 어떤 사실로 어떤 소견을 내는지, 믿으면 안 되는 자리 |
| 4 | [SCREENSHOTS.md](SCREENSHOTS.md) | 지금 화면이 어떻게 생겼는지 |

### 개발자 — 코드를 고치는 사람(사람이든 AI 코딩 에이전트든)

| 순서 | 문서 | 여기서 얻는 것 |
|---|---|---|
| 1 | [CONTRIBUTING.md](../CONTRIBUTING.md) | 빌드·검사·테스트 명령, 이슈·PR 절차 |
| 2 | [AGENTS.md](../AGENTS.md) | 모듈 경계, 안전 원칙, 화면 규칙, 작업 기록 규칙. 실제로 틀렸던 것만 적혀 있다 |
| 3 | [DESIGN.md](DESIGN.md) · [modules/](modules/) | 현재 아키텍처와 핵심 결정, Modulith가 생성한 모듈 의존 |
| 4 | [MANUAL-TEST.md](MANUAL-TEST.md) | 역할별 계정으로 변경 티켓 흐름을 손으로 확인하는 절차 |
| 5 | [VERIFICATION.md](VERIFICATION.md) | 고치려는 기능의 과거 결함과 측정 — 아래 "최근 검증 기록"에서 절 번호를 찾는다 |
| 6 | [bulk-change-spec.md](bulk-change-spec.md) | 대량 일괄 변경의 실행 방식·안전 기준 착수 명세(#101~#105, 진행 중) |

### 리뷰어·평가자 — 설계와 근거를 확인하는 사람

| 순서 | 문서 | 여기서 얻는 것 |
|---|---|---|
| 1 | [PORTFOLIO-ONEPAGER.md](PORTFOLIO-ONEPAGER.md) | 문제, 해법, 대표 수치 한 장 |
| 2 | [TECHNICAL-QA.md](TECHNICAL-QA.md) | 설계·안전·검증에 관한 질문과 답 |
| 3 | [experiments/](experiments/) | 안전장치의 비용을 잰 실험(E1~E5). 표와 CSV는 실험 코드가 생성하며 손으로 고치지 않는다 |
| 4 | [CHANGELOG.md](../CHANGELOG.md) -> [ROADMAP.md](ROADMAP.md) | 릴리스별 변경, 남은 백로그와 의도적으로 하지 않는 범위 |
| 5 | [PRESENTATION.md](PRESENTATION.md) · [PORTFOLIO-AX.md](PORTFOLIO-AX.md) | 발표 원고, AI를 운영 플랫폼에 안전하게 붙인 사례 |

## 그림

| 그림 | 내용 | 주의 |
|---|---|---|
| [architecture-detail.svg](architecture-detail.svg) | 17개 모듈, 5개 DBMS, 신뢰 경계, AI 운영 작업 실행면 | Flyway V47 기준(2026-09-18) |
| [erd.svg](erd.svg) | 핵심 데이터 도메인과 증거 관계 | V47 기준 — AI 운영 작업·대화·경보 쿨다운·수집 상태 포함 |
| [insight-flow.svg](insight-flow.svg) | 발견, AI 분석, 승인, 실행, 검증의 책임 흐름 | |
| [deployment-flow.svg](deployment-flow.svg) | CI 게이트, 멀티아키텍처 이미지, 런타임과 IaC 범위 | |

편집 원본은 같은 이름의 `.mmd`입니다. 그림을 고치면 원본과 SVG를 함께 갱신합니다.

## 최근 검증 기록

`VERIFICATION.md`는 증거 원문을 보존하므로 1만 줄이 넘습니다. 처음부터 읽지 말고 `## 184.`처럼 절 번호로 검색합니다.

| 절 | 주제 |
|---|---|
| 186~189 | 글자 크기·조회 구간 한 줄, 스냅샷 저장 실패와 수집 실패 표시, 변경 문장 테이블 구조, 확인이 필요한 DB와 원인 지름길 |
| 185 | 문서 대표 화면 재촬영과 찍다가 나온 결함 셋(스냅샷 저장 실패 #70 등) |
| 184 | 실제 화면 점검에서 나온 UI/UX 문제 — 원문 오류, 드롭다운, 쿼리 상세, 모니터링 배치 |
| 183 | v1.4.0 게시 결과 |
| 182 | 대상 DB가 죽으면 관제 화면이 줄을 서던 것 — 대상 조회 2자리 제한(#31) |
| 180·181 | 워크벤치에서 조용히 멈추던 자리, 처음 들어온 사람의 화면과 좁은 화면 실측 |
| 172·175 | 로그인 화면 정리, 화면 전체 하늘 원톤과 대비 재계산 |
| 173·176·177 | 관제 AI 칸을 채팅으로, 대화 서버 저장, AI로 나가던 값 가리기 |
| 169~171 | AI 운영 작업 — Slack 한 문장에서 검증된 소견까지, 경보가 스스로 작업을 만드는 경로 |

## 완료된 작업 기록 (보존)

당시의 감사·명세라 지금 구현과 다를 수 있습니다. 결정의 이유를 찾을 때만 읽습니다.

| 문서 | 내용 | 완료 |
|---|---|---|
| [HARDENING-ROADMAP.md](HARDENING-ROADMAP.md) | 동시성·기종 정확성·보안·수명주기 4축 감사와 FIX/SKIP 결정 | v1.1.0, 57~62절 |
| [deepening-spec.md](deepening-spec.md) | 심화 2차 착수 명세 | v1.1.0, 57~60절 |
| [OPERATIONAL-BOTTLENECK-ARCS.md](OPERATIONAL-BOTTLENECK-ARCS.md) | 운영 병목 B1~B5와 lakehouse 판정 | v1.2.0, 105~109절 |
| [PI-POLICY.md](PI-POLICY.md) | 로컬 에이전트의 도구·경로·명령 실행 정책 | 168절 |
| [eval/](eval/) | 워크벤치 자연어 SQL 평가 세트와 실행 로그 | 2026-09-10 |

## 새 내용을 어디에 적나

| 내용 | 기록할 곳 |
|---|---|
| 처음 방문한 사람이 알아야 할 제품 가치와 실행법 | 루트 `README.md` |
| 현재 아키텍처와 핵심 설계 결정 | `DESIGN.md` |
| 수집, 통계, 배포 운영 시 주의점 | `operations.md` |
| 틀렸던 적이 있어 다음 작업자가 반드시 지켜야 할 규칙 | 루트 `AGENTS.md` (근거 절 번호와 함께) |
| 명령, 출력, 측정값, 스크린샷 | `VERIFICATION.md` 새 절 |
| 착수 전에 정하는 실행 방식·안전 기준 | 기능별 `*-spec.md`(예: `bulk-change-spec.md`) |
| 릴리스별 사용자 관점의 변경 사항 | 루트 `CHANGELOG.md`의 `[Unreleased]` |
| 완료 단계, 현재 백로그, 하지 않는 범위 | `ROADMAP.md` |
| 작업의 예상·마감·실제·어긋난 이유 | GitHub 이슈 본문·댓글(작업 템플릿) |
| 발표와 포트폴리오를 위한 서사 | `PRESENTATION.md`, `PORTFOLIO-*.md` |

새 내용은 가장 알맞은 문서 한 곳에 상세히 적고 다른 문서에서는 링크합니다.
성능과 안정성 수치는 반드시 `VERIFICATION.md`의 재현 가능한 근거와 연결합니다.
