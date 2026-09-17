# <img src="docs/icon.svg" width="34" align="top"> DBTower

[![CI](https://github.com/dj258255/dbtower/actions/workflows/ci.yml/badge.svg)](https://github.com/dj258255/dbtower/actions/workflows/ci.yml)
[![Release](https://github.com/dj258255/dbtower/actions/workflows/release.yml/badge.svg)](https://github.com/dj258255/dbtower/actions/workflows/release.yml)

MySQL, PostgreSQL, SQL Server, Oracle, MongoDB를 한곳에서 관제하고 안전하게 변경하는
셀프호스트 DB 운영 플랫폼입니다. 기종별 차이는 `DbmsOperator` 뒤로 숨기고, 관제부터
진단, 승인, 실행, 전후 비교까지 하나의 흐름으로 연결합니다.

Java 21 + Spring Boot 4로 만들었으며 웹 콘솔은 별도 빌드 체인이 없는 정적 SPA입니다.

![DBTower 관제 모드 — 헬스 스코어·백업 신선도·조회 구간 한 줄·AI 어시스턴트](docs/images/webui/188-console-first-screen.jpg)

## 핵심 흐름

```text
관제 -> 문제 구간 비교 -> 실행계획·원인 분석 -> 변경 요청 -> 승인 -> 실행 -> 전후 비교
```

변경은 승인된 티켓만 실행합니다. 실행 직전 행을 복사하고, 복사한 행 수와 실제 영향 행
수가 다르면 커밋하지 않습니다. 되돌리기도 사본과 현재 행이 같은 경우에만 허용합니다.

![인덱스 제안부터 실행과 전후 비교까지](docs/images/demo-change-flow-glass.gif)

## 주요 기능

| 영역 | 제공 기능 |
|---|---|
| 관제 | 쿼리 통계, 활동 그래프, 시점 비교, Wait Event, 세션·블로킹, 복제·백업 상태 |
| 진단 | 실행계획 규칙 분석, AI 1차 분석, 심층 원인 진단, 플랜 변경·이상·데드락 감지 |
| AI 운영 작업 | Slack 한 문장·웹 콘솔·경보·DB팀 문의에서 시작된 비동기 진단. 사실은 DBTower가 모으고 모델은 1차 소견만 내며, 소견의 수치는 사실과 대조해 어긋나면 "검증되지 않음"으로 남깁니다 |
| 운영 | 통합 헬스 스코어, SLO·에러 버짓, Advisors, FinOps 신호, 백업·복원 검증 |
| 변경 관리 | 읽기 전용 워크벤치, 마스킹, 변경 리뷰, 승인 티켓 실행·되돌리기 |
| 연동 | 웹 콘솔, MCP stdio·HTTP, Discord·Slack 웹훅, K8s·Terraform·Ansible |
| 화면 | 관제·워크벤치를 한 페이지의 두 모드로. 관제 AI 채팅은 대화를 서버에 저장하고 대화 목록에서 이어 보며, AI로 보내는 쿼리 속 값은 `?`로 가립니다 |

기종별 통계와 실행 방식이 달라도 플랫폼 코드는 같은 운영 능력을 사용합니다.

| DBMS | 쿼리 통계 | 실행계획 | 백업 방식 |
|---|---|---|---|
| MySQL | `performance_schema` | JSON EXPLAIN | `mysqldump` 등 외부 CLI |
| PostgreSQL | `pg_stat_statements` | TEXT/JSON EXPLAIN | `pg_dump` 등 외부 CLI |
| SQL Server | DMV·Query Store | 플랜 XML | `BACKUP DATABASE` |
| Oracle | `V$SQL` | `DBMS_XPLAN` | `DBMS_DATAPUMP`·RMAN |
| MongoDB | profiler·server status | explain JSON | `mongodump` |

## 안전 경계

- 모니터링 조회는 읽기 전용 계정과 타임아웃을 사용합니다.
- 변경은 요청자, 승인자, 운영자의 역할을 분리하고 전 과정을 감사 기록에 남깁니다.
- MCP와 AI에는 변경 실행 도구를 노출하지 않습니다. AI는 사람이 정한 규칙 위에서
  분석만 합니다.
- 대상 DB 장애가 플랫폼 전체를 멈추지 않도록 커넥션과 수집 작업을 인스턴스별로
  격리합니다.
- 비밀번호는 AES-256-GCM으로 저장하며 API 응답과 외부 명령 인자에 노출하지 않습니다.

자세한 결정과 위협 경계는 [설계 문서](docs/DESIGN.md), [최소 권한 가이드](docs/least-privilege.md),
[검증 기록](docs/VERIFICATION.md)에서 확인할 수 있습니다.

## 빠른 시작

Docker와 Docker Compose만 있으면 앱과 전용 메타 DB를 실행할 수 있습니다.
현재 정식 버전은 `v1.4.0`입니다. 운영에서는 재현 가능한 업그레이드를 위해 `latest` 대신
`DBTOWER_TAG=1.4.0`처럼 버전을 고정하는 것을 권장합니다.

```bash
cp .env.example .env
```

`.env`에서 최소 두 값을 설정합니다.

```dotenv
DBTOWER_DB_PASSWORD=change-me-strong-password
DBTOWER_ENCRYPTION_KEY=<openssl rand -base64 32 결과>
DBTOWER_TAG=1.4.0
```

그다음 컨테이너를 시작합니다.

```bash
docker compose -f docker-compose.app.yml up -d
```

웹 콘솔은 <http://localhost:8080>에서 열립니다. `DBTOWER_ADMIN_PASSWORD`를 비워 두었다면
최초 관리자 비밀번호는 다음 명령으로 확인합니다.

```bash
docker compose -f docker-compose.app.yml logs dbtower
```

이 구성은 DBTower와 메타 DB만 실행합니다. 관리 대상 DB는 로그인 후 웹 콘솔에서
등록합니다.
배포와 프록시, 백업 설정은 [운영 가이드](docs/operations.md)를 참고하세요.

## 로컬 개발

JDK 21과 Docker가 필요합니다. 아래 compose는 개발용 대상 DB 5종과 모니터링 스택을 띄웁니다.

```bash
docker compose up -d
DBTOWER_WEBHOOK_URL="" DBTOWER_ADMIN_PASSWORD=devpass \
DBTOWER_ENCRYPTION_KEY=$(openssl rand -base64 32) ./gradlew bootRun
```

Apple Silicon의 Rosetta 없는 Colima에서는 amd64 전용 SQL Server 2022가 기동되지 않습니다.
이 머신에서 `docker-compose.arm64.yml`의 Azure SQL Edge도 `S_SbtUnimplementedInstruction`로
종료됐다. SQL Server까지 확인할 때는 Rosetta를 켠 별도 Colima 프로필에 실제 2022 이미지를 띄운다.

```bash
colima start mssql2022 --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 20
MSSQL_SA_PASSWORD='...' docker --context colima-mssql2022 run -d \
  --name dbtower-mssql2022 --platform linux/amd64 \
  -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD -p 14330:1433 \
  mcr.microsoft.com/mssql/server:2022-latest
```

계정·데모 데이터 준비와 실제 SQL Server 2022 검증 결과는
[VERIFICATION.md](docs/VERIFICATION.md) 132·164절에 기록했습니다. 이 실행은 Microsoft가
지원하는 네이티브 ARM64 구성이 아니라 로컬 개발용 번역 환경이다.

등록한 대상의 비밀번호를 다음 실행에서도 사용하려면 암호화 키를 고정해서
보관해야 합니다.
키 없이 로컬에서만 실행하려면 `SPRING_PROFILES_ACTIVE=dev`를 사용할 수 있습니다.

변경을 마치기 전에는 저장소 규칙에 따라 세 검증을 모두 실행합니다.

```bash
./gradlew compileJava
./scripts/check-conventions.sh
./gradlew test
```

## 한 플랫폼, 사람별 입구

| 역할 | 주 사용자 | 주요 권한 |
|---|---|---|
| `VIEWER` | 온콜·팀장 | 관제, 비교, 리포트 조회 |
| `REQUESTER` | 개발자·데이터 요청자 | 워크벤치 조회, 변경 요청 |
| `APPROVER` | DBA 리드 | 변경 승인·반려, 드라이런 |
| `OPERATOR` | DBA 운영자 | 승인 티켓 실행·되돌리기, 백업, 세션 종료 |
| `ADMIN` | 플랫폼 관리자 | 인스턴스, 계정, 보안, 감사 관리 |

승인자와 운영자는 서로를 포함하지 않습니다. 화면은 `/api/me`가 반환한 능력으로 동작을
표시하고, 최종 권한은 서버가 검사합니다.

직접 역할을 바꿔 가며 확인하는 순서는 [역할별 수동 테스트](docs/MANUAL-TEST.md)에 있습니다.

![DBTower 워크벤치 모드 — 스키마 트리, 워크시트 탭, 조회 결과, 버전 기록](docs/images/webui/196-workbench-query-result.jpg)

## 아키텍처

플랫폼은 Spring Modulith 기반 17개 모듈로 나뉩니다. 다른 모듈은 공개 서비스와 DTO만
사용하며 구현, 엔티티, 저장소는 각 모듈의 `internal` 패키지에 숨깁니다. 순환 의존과
레이어 위반은 테스트와 규약 검사에서 실패합니다.

![DBTower 아키텍처](docs/architecture-detail.svg)

핵심은 `operator` 모듈입니다. 새 DBMS 지원은 Operator 구현체가 본체이고 enum, 팩토리,
백업 도구, JDBC 드라이버, 화면 자산은 등록 절차로 다룹니다. 전체 구조는
[DESIGN.md](docs/DESIGN.md), 생성된 모듈 문서는 [docs/modules/](docs/modules/)에 있습니다.

## 실측 결과

성능과 안정성 주장은 모두 재현 가능한 로그를 남깁니다. 대표 결과만 추리면 다음과
같습니다.

| 검증 | Before | After |
|---|---:|---:|
| 시점 비교 조회, 50만 행 | 21.269 ms | 0.062 ms |
| 같은 부하의 인덱스 티켓 전후 평균 지연 | 46.378 ms | 0.521 ms |
| 무응답 대상이 폴러 전체를 막던 시간 | 25초 초과 | 약 2초 |
| 세션 화면 10명의 30초간 대상 조회 | 150회 | 14회 |
| Slack 한 문장에서 검증된 소견까지(라이브) | 확인 응답 65.6 ms | 결과 도착 31.7초 |

측정 환경, 명령, 원문 출력은 [VERIFICATION.md](docs/VERIFICATION.md)의 9, 132, 134,
140, 169절에 있습니다.

## 일정과 작업 방식

개발은 2026년 3월에 시작했고 지금까지 커밋 483개가 쌓였습니다. 태그와
[CHANGELOG.md](CHANGELOG.md)를 기준으로 단계를 나누면 다음과 같습니다.

| 기간 | 단계 | 산출물 |
|---|---|---|
| 2026-03 ~ 06월 | MVP 세 가지(이기종 등록·헬스체크, 시점 비교, EXPLAIN 규칙 분석)와 성능·확장 작업 | 태그 이전, 커밋 42개 |
| 2026-07-06 ~ 07-07 | 5기종 지원과 인증·암호화·감사·백업 복원 검증으로 첫 공개, 이어서 심화 네 아크로 다섯 기종을 고르게 다듬고 하드닝 | v1.0.0, v1.0.1, v1.1.0 |
| 2026-07-19 | 운영 병목 다섯 곳(설정 드리프트, 리뷰 게이트, 인덱스 사용 통계, 인시던트, 월간 리포트)을 끊고 콘솔을 사이드바 구조로 개편 | v1.2.0 |
| 2026-07-19 ~ 09-14 | 거버넌스 SQL 워크벤치(조회부터 승인 실행까지), 역할 5종 분리, 실시간 세션 관제, 세션 샘플링 | v1.3.0, v1.3.1 |
| 2026-09-15 ~ 09-17 | Slack 등 네 입구를 하나의 AI 운영 작업 모델로 묶고 콘솔 화면을 사람이 쓰는 순서로 다시 짬 | v1.4.0 |
| 2026-09-17 ~ 진행 중 | 화면 재설계 2차, 대량 일괄 변경 준비 | v1.5.0(마일스톤, 진행 중) |

이슈와 PR로 작업을 나누는 방식은 v1.3.0 전후부터 자리잡았습니다. 문제는 이슈로 먼저
적습니다. 고친 내용은 PR로 올립니다. 실제로 돌려 본 명령과 출력은 VERIFICATION.md에
절 번호를 붙여 남깁니다. 배포되는 변경은 CHANGELOG에 적습니다. 착수할 작업은
[작업 이슈 템플릿](.github/ISSUE_TEMPLATE/task.md)으로 예상과 예상 마감을 먼저 적고
끝나면 실제 마감과 어긋난 이유를 채웁니다([CONTRIBUTING.md](CONTRIBUTING.md) 참고).
지금까지 이슈 44건(열림 15건, 닫힘 29건), PR 62건이 쌓였습니다.

예상 시간은 2026-09-17부터 기록을 시작했습니다. 그 전까지는 로드맵과 명세 문서에
우선순위와 완료 조건만 있고 예상 시간은 없었습니다. 아래는 그날 이후 실제로 적어 둔
이슈 몇 건의 예상과 실제입니다.

| 이슈 → PR | 작업 | 예상 | 예상 마감 | 실제 마감 | 어긋난 이유 |
|---|---|---|---|---|---|
| #66 → PR #67 | UX 1차 검증 기록과 CHANGELOG 정리 | 2시간 | 09-17 23:20 | 09-17 21:54 | 앞선 작업에서 이미 확인한 수치와 화면이라 새로 잴 것이 없었습니다 |
| #61 → PR #68 | 글자 크기 네 단계 기준 적용, 시간대 선택 접기 | 4시간 | 09-18 07:20 | 09-17 23:04 | 값 대응표 하나로 한 번에 바꿨습니다 |
| #69 → PR #74 | 문서 대표 화면 10장 재촬영 | 2시간 | 09-18 00:40 | 09-17 23:15 | 촬영 중 발견한 스냅샷 저장 실패(#70)를 먼저 고치는 작업이 끼었습니다 |
| #70 → PR #71 | 스냅샷 저장 실패 수정 | 1시간 | 09-17 23:43 | 09-17 23:08 | 수정 전 재현에서 수집 재시도 간격만큼 약 10분을 기다렸습니다 |

## 문서

문서가 길어져도 README가 다시 목차가 되지 않도록 별도의 [문서 안내](docs/README.md)에
목적별 읽기 순서와 기록 위치를 정리했습니다.

- 처음 둘러보기: [한 장 요약](docs/PORTFOLIO-ONEPAGER.md), [5분 시연](docs/DEMO-5MIN.md)
- 설계 이해하기: [설계](docs/DESIGN.md), [AI 판단 규칙](docs/ai-analysis-rules.md), [AI 운영 자동화](docs/AI-OPERATIONS-AUTOMATION.md)
- 운영·배포하기: [운영](docs/operations.md), [최소 권한](docs/least-privilege.md)
- API 연동하기: [API 빠른 참조](docs/API.md)
- 직접 눌러보기: [역할별 수동 테스트](docs/MANUAL-TEST.md)
- 현재 화면 보기: [화면 갤러리](docs/SCREENSHOTS.md)
- 근거 확인하기: [검증 기록](docs/VERIFICATION.md), [변경 이력](CHANGELOG.md)
- 개발에 참여하기: [기여 가이드](CONTRIBUTING.md), [저장소 규칙](AGENTS.md)

## 요구사항

| 용도 | 요구사항 |
|---|---|
| 셀프호스트 | Docker, Docker Compose, 앱 컨테이너 메모리 512MB~1GB 권장 |
| 소스 개발 | JDK 21, Docker |
| 메타 DB | PostgreSQL 16, compose에 포함 |
| 관리 대상 | MySQL 8.0+, PostgreSQL 13+, SQL Server 2019+, Oracle 19c/Free, MongoDB 6.0+ |

Apache-2.0 라이선스로 배포합니다. 자세한 내용은 [LICENSE](LICENSE)와 [NOTICE](NOTICE)를
참고하세요.
