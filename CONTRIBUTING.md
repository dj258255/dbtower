# 기여 가이드

DBTower에 기여해 주셔서 감사합니다. 이 문서는 기여 절차를, 코드 규칙은 [AGENTS.md](AGENTS.md)를 봅니다.

## 시작하기

```bash
./gradlew compileJava     # 컴파일
./gradlew test            # 테스트 (실 DB 불필요 — 테스트는 H2 인메모리)
docker compose up -d      # 관리 대상 DB 5종 + 모니터링 (기능 검증용)
DBTOWER_WEBHOOK_URL="" ./gradlew bootRun   # 앱 기동 (http://localhost:8080)
```

## 변경을 올리기 전에

- `./gradlew test`가 통과해야 합니다. Spring Modulith 경계(`ModularityTests`)도 여기서 강제됩니다.
- 새 모듈 코드는 [AGENTS.md](AGENTS.md)의 "모듈 내부 패키지 규칙"을 따릅니다 — 공개 API만 모듈 루트,
  구현은 `internal/`.
- 성능 개선은 before/after 실측 수치와 함께. 측정 없는 개선 주장은 받지 않습니다.
- 기능 검증 결과(명령·출력·스크린샷)는 `docs/VERIFICATION.md`에 절을 추가해 기록합니다.
- 커밋 메시지는 한국어, 제목에 변경 의도가 드러나게. 이모지는 쓰지 않습니다.

## 이슈·PR

- 버그·기능 제안은 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)을 채워 주세요.
- PR은 작은 논리 단위로 나누고, 무엇을 왜 바꿨는지와 검증 방법을 본문에 적어 주세요.

## 행동 강령

이 프로젝트는 [Contributor Covenant](CODE_OF_CONDUCT.md)를 따릅니다.
