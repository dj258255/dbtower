# DBTower — 에이전트/기여 가이드

이 저장소에서 코드를 수정할 때 지켜야 할 규칙. 저장소 전반의 가이드이며, 작업별 지시는 여기에 두지 않는다.

## 변경을 마치기 전에 반드시 검증한다

```bash
./gradlew compileJava          # 컴파일
./gradlew test                 # 테스트
docker compose up -d           # 대상 DB 5종 + 모니터링 스택
DBTOWER_WEBHOOK_URL="" ./gradlew bootRun   # 앱 기동 (http://localhost:8080)
```

- 성능 개선은 반드시 before/after 실측 수치와 함께. 측정 없는 개선 주장 금지
- 기능 검증 결과(명령·출력·스크린샷)는 docs/VERIFICATION.md에 절을 추가해 기록한다
- 수치를 지어내지 않는다. 재현 불가능한 주장은 쓰지 않는다

## 저장소 구조

```text
src/main/java/io/dbtower/
├── operator/    DbmsOperator 인터페이스 + 5기종 구현(MySQL/PostgreSQL/MSSQL/Oracle/MongoDB), 커넥션 풀/클라이언트 캐시
├── registry/    인스턴스 등록·헬스체크
├── insight/     스냅샷 수집, 시점 비교, 활동 그래프
├── analysis/    실행계획 규칙 기반 분석
├── alert/       회귀 감지, 웹훅, AI 1차 분석
├── backup/      백업 정책·실행·폴러
└── mcp/         MCP 서버 (프로토콜 코어 + stdio/HTTP 전송)
src/main/resources/static/   웹 콘솔 (의존성 0 정적 SPA)
docs/            DESIGN, VERIFICATION(실측 기록), PRESENTATION, ROADMAP, ai-analysis-rules
scripts/         dbtower-mcp.sh (MCP stdio 실행기)
```

## 아키텍처 원칙

- 플랫폼 코드는 `DbmsOperator` 인터페이스에만 의존한다. 기종 분기는 `DbmsOperatorFactory` 한 곳에만 존재
- 새 DBMS 지원 = Operator 구현체 1개 추가로 끝나야 한다
- 플랫폼 자체 저장소(PostgreSQL, dbtower DB)와 관리 대상 DB는 분리 — 대상 장애가 플랫폼을 죽이면 안 된다
- 관리 플랫폼은 대상 DB에 임의 DML을 실행하지 않는다 (explain은 SELECT만 허용)
- MCP·웹훅 등 채널 계층에 비즈니스 로직을 두지 않는다 — 전부 REST/서비스 코어에 위임
- AI는 판단자가 아니라 1차 분석기다. 판단 기준은 docs/ai-analysis-rules.md에 사람이 정하고, AI는 그 위에서만 판정

## 코드 컨벤션

- Java 21 + Spring Boot 4. Lombok 미사용 — 값 객체는 record, JPA 엔티티는 명시적 생성자/게터
  (@Data/@ToString은 lazy 연관관계 지뢰)
- 주석은 "왜"를 설명할 때만, 한국어로
- 이모지 금지 — 코드/문서/커밋 메시지 전부
- 커밋 메시지는 한국어, 제목은 변경의 의도가 드러나게
- 프론트(정적 SPA)는 프레임워크·빌드체인 없이 유지한다. innerHTML에 들어가는 동적 값은 반드시 esc() 경유

## 비밀값

- 비밀번호·웹훅 URL·API 키를 커밋하지 않는다. 환경변수로만 주입 (DBTOWER_DB_PASSWORD, DBTOWER_WEBHOOK_URL, ANTHROPIC_API_KEY)
- 외부 명령 실행 시 비밀번호는 argv 금지 — 환경변수(MYSQL_PWD/PGPASSWORD)로 전달
- API 응답에 접속 자격증명을 노출하지 않는다

## 테스트

- 구현 디테일보다 동작을 검증한다
- 핵심 대상: ComparisonService(카운터 차분·구간 경계), RegressionDetector(규칙·쿨다운),
  AbstractJdbcOperator.renderCommand(명령 주입 방어)
- 대상 DB가 필요한 검증은 docker compose 기반으로 재현 가능하게
