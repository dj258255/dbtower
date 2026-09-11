# DBTower — 에이전트/기여 가이드

이 저장소에서 코드를 수정할 때 지켜야 할 규칙. 저장소 전반의 가이드이며, 작업별 지시는 여기에 두지 않는다.

## 변경을 마치기 전에 반드시 검증한다

```bash
./gradlew compileJava          # 컴파일
./scripts/check-conventions.sh # 규약 검사 (모듈 경계·Lombok·이모지·기종 분기 기준선)
./gradlew test                 # 테스트
docker compose up -d           # 대상 DB 5종 + 모니터링 스택
# 앱 기동 — 암호화 키는 필수다(프로필 미설정은 fail-closed. SecretCipher 주석 참고).
# 키 없이 로컬에서 띄우려면 SPRING_PROFILES_ACTIVE=dev 를 준다.
DBTOWER_ENCRYPTION_KEY=$(openssl rand -base64 32) DBTOWER_WEBHOOK_URL="" ./gradlew bootRun
```

- 성능 개선은 반드시 before/after 실측 수치와 함께. 측정 없는 개선 주장 금지
- 기능 검증 결과(명령·출력·스크린샷)는 docs/VERIFICATION.md에 절을 추가해 기록한다
- 수치를 지어내지 않는다. 재현 불가능한 주장은 쓰지 않는다

## 저장소 구조

```text
src/main/java/io/dbtower/    Spring Modulith 모듈 16개 (순환·internal 침범은 ModularityTests가,
                             레이어 규칙은 scripts/check-conventions.sh가 빌드에서 강제)
├── operator/    DbmsOperator 인터페이스 + 5기종 구현(MySQL/PostgreSQL/MSSQL/Oracle/MongoDB), 커넥션 풀/클라이언트 캐시
├── registry/    인스턴스 등록·헬스체크
├── insight/     스냅샷 수집, 시점 비교, 활동 그래프, 파라미터/스키마 diff
├── analysis/    실행계획 규칙 기반 분석 + AI 1차 분석 + 심층 진단
├── alert/       회귀·이상·운영 감지(복제 지연·역할변경(failover)·split-brain 관측 포함), 웹훅, 플랜 변경 감지, DB팀 문의
├── backup/      백업 정책·실행·복원 검증·원격 보관·신선도
├── advisor/     운영 모범규칙 자동 점검 (일일 스윕)
├── audit/       상태변경·로그인·월권 감사 기록
├── finops/      미사용·중복 인덱스 등 낭비 신호
├── mcp/         MCP 서버 (프로토콜 코어 + stdio/HTTP 전송) + 자연어 진단 + 워크벤치 요청·조회 도구(실행 도구 없음)
├── onlineddl/   gh-ost 온라인 스키마 변경 (MySQL)
├── review/      변경 리뷰 게이트 (판정·승인·상태 전이의 단일 권위 — 실행권은 ChangeTicketGate의 조건부 UPDATE로만)
├── score/       통합 헬스 스코어 + 대상별 운영 종합(overview: 정체·건강·복제·백업을 한 대상에)
├── security/    인증·인가, 비밀번호 암호화, API 토큰
├── slo/         SLO/에러 버짓
└── workbench/   거버넌스 SQL 워크벤치 (조회 콘솔·AI 보조·승인 티켓 실행·행/구조/계획/인스턴스 간 전후 비교)
src/main/resources/static/   웹 콘솔 (의존성 0 정적 SPA)
docs/            DESIGN, VERIFICATION(실측 기록), PRESENTATION, ROADMAP, ai-analysis-rules
scripts/         dbtower-mcp.sh (MCP stdio 실행기)
```

모듈 내부 패키지 규칙: 모듈 루트에는 다른 모듈이 쓰는 공개 API(서비스·record/DTO)만 두고,
구현은 `internal/`(컨트롤러는 `internal/web`, JPA 엔티티는 `internal/domain`, 리포지토리는
`internal/persistence`, 폴러/잡은 `internal/job`)에 숨긴다 — Modulith가 외부 접근을 차단한다.
다른 모듈의 리포지토리·엔티티를 직접 참조하지 않는다(그 모듈의 공개 서비스로 우회).
공개 API 타입의 시그니처·record 컴포넌트에 등장하는 타입은 루트에 남긴다.

## 아키텍처 원칙

- 플랫폼 코드는 `DbmsOperator` 인터페이스에만 의존한다. 같은 능력의 기종별 **구현 선택**은
  `DbmsOperatorFactory` 한 곳에만 둔다 — 다른 곳에서 `DbmsType`으로 구현을 갈라내지 않는다
- 능력 자체가 없는 기종을 걸러내는 선언(`Advisor.supports(DbmsType)` 등)은 위 금지 대상이 아니다.
  다만 늘어나면 유지비가 붙으므로 기준선을 `scripts/check-conventions.sh`가 지킨다 —
  새 분기를 추가하기 전에 `DbmsOperator`에 능력으로 흡수할 수 있는지 먼저 검토한다
- 새 DBMS 지원의 **핵심**은 Operator 구현체 1개다. 다만 실제로는 그 밖에도 손댈 곳이 있다 —
  `DbmsType` enum, 팩토리 case, 기종별 백업 명령 설정(`BackupTools`·`application.yml`),
  JDBC 드라이버 의존성, 프론트의 아이콘·색상. "1개로 끝난다"가 아니라
  "1개가 본체이고 나머지는 등록 절차"로 이해할 것 (컴파일러가 잡아주는 곳은 팩토리·`DeepAnalyzer`·
  `RuleBasedAnalyzer`의 exhaustive switch 셋뿐이므로, 새 기종 추가 시 위 목록을 직접 훑어야 한다)
- 플랫폼 자체 저장소(PostgreSQL, dbtower DB)와 관리 대상 DB는 분리 — 대상 장애가 플랫폼을 죽이면 안 된다.
  연결만 받고 말이 없는 대상도 포함한다: 풀 생성이 연결을 붙잡지 않고, 드라이버 로그인 단계에 읽기 제한이 있어야 한다(ConnectionPools·UnresponsiveTargetTest)
- 관리 플랫폼은 대상 DB에 임의 변경을 실행하지 않는다 — 조회는 분리된 조회 계정·읽기 전용 트랜잭션으로, 변경은
  **승인된 티켓만** 변경 계정으로 실행한다(행 사본·영향 행 수 대조·되돌리기 경로와 함께). 모니터 계정의 explain은 SELECT만 허용
- 변경 실행의 안전은 문장 해석이 아니라 실행 계층의 불변식에서 나온다 — 파서가 틀려도 사본 행 수와 영향 행 수가
  어긋나면 커밋하지 않고, 되돌리기는 실행 직후 사본과 현재 행이 같을 때만 쓴다(JdbcChangeRunner 주석 참고)
- MCP·웹훅 등 채널 계층에 비즈니스 로직을 두지 않는다 — 전부 REST/서비스 코어에 위임. 위임의 주체는 호출자 그대로 둔다
  (서비스 토큰으로 바꿔 부르면 요청자·감사 기록이 사람을 잃는다 — McpHttpController 주석)
- AI는 판단자가 아니라 1차 분석기다. 판단 기준은 docs/ai-analysis-rules.md에 사람이 정하고, AI는 그 위에서만 판정

## 코드 컨벤션

- Java 21 + Spring Boot 4. 값 객체는 record. JPA 엔티티는 Lombok @Getter/@NoArgsConstructor까지만 —
  @Data/@ToString/@Setter 금지 (lazy 연관관계 지뢰, 무분별한 가변성)
- 주석은 "왜"를 설명할 때만, 한국어로
- 이모지 금지 — 코드/문서/커밋 메시지 전부
- 커밋 메시지는 한국어, 제목은 변경의 의도가 드러나게
- 프론트(정적 SPA)는 프레임워크·빌드체인 없이 유지한다. innerHTML에 들어가는 동적 값은 반드시 esc() 경유

## 화면(웹 콘솔)

실제로 한 번씩 틀렸던 것들이다(docs/VERIFICATION.md 138·139·140절).

- 유리 재질(`--glass-*` 토큰)은 떠 있는 계층에만 쓴다 — 상단바·사이드바·세그먼트 탭·드롭다운·툴팁·모달·로그인 카드.
  표·카드·코드·편집기는 불투명. 운영 콘솔은 읽는 화면이다
- 글자 대비는 4.5:1 이상을 눈대중이 아니라 계산으로 확인한다(흰 바탕 보조 글자 #667085, 유리 위 #5b6475)
- 한 열로 접히는 그리드는 `1fr`이 아니라 `minmax(0, 1fr)` — `1fr`의 최소 폭은 내용(nowrap 표)이라 페이지를 옆으로 민다
- 탭은 번갈아 보는 것끼리만 묶는다 — 함께 봐야 쓰는 둘(스키마 -> 채팅 칩)을 한 탭 묶음에 넣으면 서로를 가린다.
  참고 제품의 탭을 옮길 때는 모양이 아니라 그 칸의 역할을 옮긴다(147절: TOI의 AI 재료 탭을 흉내 내 스키마·티켓을 채팅 탭에 넣었다)
- 표 셀의 `max-width`는 자동 표 레이아웃에서 무시된다 — 말줄임 열은 `max-width: 0; width: 100%`, 표 셀 안에 끼운 상세는
  `width: 0; min-width: 100%`로 열 폭 계산에서 뺀다(146절: Top Query 표가 1512px에서 옆으로 넘쳤다)
- 한 열로 접는 기준은 뷰포트가 아니라 담는 칸 폭이다(`@container`) — 같은 렌더러가 넓은 화면의 좁은 칸(워크벤치 가운데 약 775px, 관제 펼침)에 들어간다.
  두 열로 두니 열 표의 타입이 글자 중간에서 쪼개졌다(151절)
- SVG 요소는 `el.hidden = true`로 감춰지지 않는다(HTMLElement 전용 프로퍼티) — `toggleAttribute("hidden", ...)`
- 좁은 화면 확인은 Playwright로 뷰포트를 정확히 맞춰 잰다. Chrome 확장 창 조절(innerWidth 그대로)·iframe(X-Frame-Options)·
  headless `--window-size`(뷰포트가 넓게 잡힘)는 모두 틀린 결과를 냈다
- 닫힌 `<details>` 안 요소도 Chrome에서는 박스를 가져 `offsetParent`·크기가 잡힌다 — 접힘 효과는 바깥 요소 높이로 잰다(151절 측정에서 틀렸다)
- 대상 DB를 주기적으로 조회하는 화면은 브라우저가 각자 폴링하지 않고 서버 허브를 거친다(LiveSessionHub) — 폴링은 대상 조회가 보는 사람 수만큼 는다
- 관제와 워크벤치는 한 페이지(index.html)의 두 모드다 — 모드를 바꾸면 숨긴 쪽의 실시간 연결을 닫고(setMode -> syncLive), 워크벤치 코드는 처음 들어갈 때
  모듈로 불러온다. `/workbench.html`은 `/?mode=workbench`로 넘기는 페이지로만 남긴다(149절)
- 실시간 연결은 화면이 안 보이면(탭 숨김·다른 그룹) 닫는다. 안 보는 화면이 구독자로 남으면 대상 조회가 멈추지 않는다
- AI 응답처럼 긴 대기는 한 번에 받지 않고 흘려 보여준다. 흘린 조각은 표시용이고 저장·분류는 완성본으로 한다

## 비밀값

- 비밀번호·웹훅 URL·API 키를 커밋하지 않는다. 환경변수로만 주입 (DBTOWER_DB_PASSWORD, DBTOWER_WEBHOOK_URL, ANTHROPIC_API_KEY)
- 외부 명령 실행 시 비밀번호는 argv 금지 — 환경변수(MYSQL_PWD/PGPASSWORD)로 전달
- API 응답에 접속 자격증명을 노출하지 않는다

## 테스트

- 구현 디테일보다 동작을 검증한다
- 핵심 대상: ComparisonService(카운터 차분·구간 경계), RegressionDetector(규칙·쿨다운),
  AbstractJdbcOperator.renderCommand(명령 주입 방어)
- 대상 DB가 필요한 검증은 docker compose 기반으로 재현 가능하게
