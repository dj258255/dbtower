# DBTower 문서 안내

README에는 제품 개요와 실행에 필요한 최소 정보만 둡니다. 이 문서는 목적에 맞는 상세
문서를 빠르게 찾기 위한 지도입니다.

## 처음 보는 경우

1. [한 장 요약](PORTFOLIO-ONEPAGER.md)에서 문제, 해법, 대표 수치를 확인합니다.
2. [5분 시연](DEMO-5MIN.md)으로 실제 사용자 흐름을 따라갑니다.
3. 직접 눌러 보려면 [역할별 수동 테스트](MANUAL-TEST.md)를 따라갑니다.
4. [현재 화면 갤러리](SCREENSHOTS.md)에서 관제·워크벤치·거버넌스 화면을 봅니다.
5. [설계 문서](DESIGN.md)에서 모듈 경계와 핵심 결정을 읽습니다.

전체 발표 서사는 [PRESENTATION.md](PRESENTATION.md), 설계 결정의 짧은 문답은
[TECHNICAL-QA.md](TECHNICAL-QA.md)에서 확인합니다.

## 주제별 문서

### 제품과 설계

| 문서 | 역할 |
|---|---|
| [DESIGN.md](DESIGN.md) | 현재 아키텍처, 핵심 결정, 안전 원칙 |
| [ai-analysis-rules.md](ai-analysis-rules.md) | 기종별 실행계획 판단 근거와 예외 |
| [modules/](modules/) | Spring Modulith가 생성한 모듈 구조와 의존 관계 |
| [architecture-detail.svg](architecture-detail.svg) | 16개 모듈, 5개 DBMS, 신뢰 경계를 한 장으로 본 구조도 |
| [erd.svg](erd.svg) | Flyway V41 기준 핵심 데이터 도메인과 증거 관계 |
| [insight-flow.svg](insight-flow.svg) | 발견, AI 분석, 승인, 실행, 검증의 책임 흐름 |
| [deployment-flow.svg](deployment-flow.svg) | CI 게이트, 멀티아키텍처 이미지, 런타임과 IaC 범위 |
| Mermaid 원본 | [아키텍처](architecture-detail.mmd) · [데이터](erd.mmd) · [진단 흐름](insight-flow.mmd) · [배포](deployment-flow.mmd) |

### 운영과 배포

| 문서 | 역할 |
|---|---|
| [operations.md](operations.md) | 수집, 통계, 알림, 배포 운영 시 주의점 |
| [least-privilege.md](least-privilege.md) | DBMS별 모니터링 계정 최소 권한 |
| [API.md](API.md) | 인증 방식과 주요 REST·MCP 진입점 |
| [MANUAL-TEST.md](MANUAL-TEST.md) | 역할별 계정과 변경 티켓 수동 테스트 |
| [SCREENSHOTS.md](SCREENSHOTS.md) | 현재 대표 화면과 캡처 갱신 원칙 |
| [Kubernetes](../infra/k8s/README.md) | CloudNativePG 생성 후 DBTower 등록 |
| [Terraform](../infra/terraform/README.md) | RDS 프로비저닝과 자동 등록 |
| [Ansible](../infra/ansible/README.md) | VM DB 구성과 자동 등록 |

### 계획과 근거

| 문서 | 역할 |
|---|---|
| [ROADMAP.md](ROADMAP.md) | 완료 단계, 현재 백로그, 의도적으로 하지 않는 범위 |
| [VERIFICATION.md](VERIFICATION.md) | 명령, 출력, 측정값, 스크린샷을 보존하는 실험 노트 |
| [CHANGELOG.md](../CHANGELOG.md) | 릴리스별 사용자 관점의 변경 사항 |
| [HARDENING-ROADMAP.md](HARDENING-ROADMAP.md) | 보안·수명주기·기종 정확성 감사 기록 |
| [OPERATIONAL-BOTTLENECK-ARCS.md](OPERATIONAL-BOTTLENECK-ARCS.md) | 운영 병목 B1~B5 작업 기록 |
| [deepening-spec.md](deepening-spec.md) | 심화 작업 당시의 착수 명세 |

`VERIFICATION.md`는 증거 원문을 보존하므로 의도적으로 깁니다. 처음부터 읽기보다 문서
안에서 `## 140.`처럼 절 번호를 검색하거나, 다른 문서가 가리키는 절부터 읽는 편이 빠릅니다.
`ROADMAP.md`의 완료 항목도 결정 이력을 보존하기 위해 남겨 둡니다.

### 포트폴리오와 발표

| 문서 | 역할 |
|---|---|
| [PORTFOLIO-ONEPAGER.md](PORTFOLIO-ONEPAGER.md) | 프로젝트 한 장 요약 |
| [PORTFOLIO-AX.md](PORTFOLIO-AX.md) | AI를 운영 플랫폼에 안전하게 연결한 사례 |
| [PRESENTATION.md](PRESENTATION.md) | 문제 정의부터 결과까지의 발표 원고 |
| [TECHNICAL-QA.md](TECHNICAL-QA.md) | 설계·안전·검증에 관한 기술 질문과 답 |
| [DEMO-5MIN.md](DEMO-5MIN.md) | 역할을 바꿔 가며 진행하는 짧은 시연 순서 |

## 읽는 목적별 경로

| 목적 | 권장 순서 |
|---|---|
| 빠르게 평가 | 한 장 요약 -> 5분 시연 -> 역할별 수동 테스트 |
| 설계 검토 | DESIGN -> modules -> AI 판단 규칙 -> 최소 권한 |
| 운영 준비 | 루트 README 빠른 시작 -> operations -> least-privilege -> infra |
| 변경 이력 확인 | CHANGELOG -> ROADMAP -> 필요한 VERIFICATION 절 |
| 기여 준비 | CONTRIBUTING -> AGENTS -> DESIGN |

## 문서별 기록 원칙

중복을 줄이기 위해 새 내용을 추가할 때 다음 기준을 사용합니다.

| 내용 | 기록할 곳 |
|---|---|
| 처음 방문한 사람이 알아야 할 제품 가치와 실행법 | 루트 `README.md` |
| 현재 아키텍처와 핵심 설계 결정 | `DESIGN.md` |
| 수집, 통계, 배포 운영 시 주의점 | `operations.md` |
| 완료 단계, 현재 백로그, 의도적으로 하지 않는 범위 | `ROADMAP.md` |
| 명령, 출력, 측정값, 스크린샷 | `VERIFICATION.md` |
| 릴리스별 사용자 관점의 변경 사항 | 루트 `CHANGELOG.md` |
| 발표와 포트폴리오를 위한 서사 | `PRESENTATION.md`, `PORTFOLIO-*.md` |

새 내용은 가장 알맞은 문서 한 곳에 상세히 기록하고 다른 문서에서는 링크합니다.
성능과 안정성 수치는 반드시 `VERIFICATION.md`의 재현 가능한 근거와 연결합니다.
