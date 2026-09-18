# 기여 가이드

DBTower에 기여해 주셔서 감사합니다. 이 문서는 기여 절차를 설명합니다. 코드 규칙은
[AGENTS.md](AGENTS.md)를 봅니다.

## 시작하기

```bash
./gradlew compileJava
./scripts/check-conventions.sh
./gradlew test
docker compose up -d
DBTOWER_WEBHOOK_URL="" DBTOWER_ENCRYPTION_KEY=$(openssl rand -base64 32) ./gradlew bootRun
```

웹 콘솔은 <http://localhost:8080>에서 열립니다. 등록한 대상의 비밀번호를 다음 실행에서도
사용하려면 암호화 키를 고정해서 보관해야 합니다. 키 없이 로컬에서만 실행하려면
`SPRING_PROFILES_ACTIVE=dev`를 사용할 수 있습니다.

## 변경을 올리기 전에

- 컴파일, 규약 검사, 테스트가 모두 통과해야 합니다. Spring Modulith 경계(`ModularityTests`)도
  테스트에서 강제됩니다.
- 새 모듈 코드는 [AGENTS.md](AGENTS.md)의 "모듈 내부 패키지 규칙"을 따릅니다.
  공개 API만 모듈 루트에 두고 구현은 `internal/`에 둡니다.
- 성능 개선은 before/after 실측 수치와 함께. 측정 없는 개선 주장은 받지 않습니다.
- 기능 검증 결과(명령·출력·스크린샷)는 `docs/verify/VERIFICATION.md`에 절을 추가해 기록합니다.
- 커밋 메시지는 한국어, 제목에 변경 의도가 드러나게. 이모지는 쓰지 않습니다.

## 이슈·PR

- 버그·기능 제안은 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)을 채워 주세요. 착수할 작업은 "작업" 템플릿으로
  예상(시간·일 단위)·예상 마감(날짜만)·완료 조건을 먼저 적고, 끝나면 실제 마감과 어긋난 이유를 채웁니다.
- 문제 하나에 이슈 하나, 영역 하나에 PR 하나, 변경 한 가지에 커밋 하나로 나눕니다.
  이슈·PR에는 `type:*`·`area:*` 라벨과 마일스톤을 답니다.
- 같은 파일을 고치는 PR을 쌓았다면 본문에 머지 순서를 적고, 머지할 때 head 브랜치를 바로 지우지 않습니다
  (지우면 GitHub가 그 위에 쌓인 PR을 닫습니다). 자세한 규칙은 [AGENTS.md](AGENTS.md)의 "작업 기록"에 있습니다.
- 화면이 바뀐 PR은 머지 전에 실제 브라우저에서 확인합니다.

## 행동 강령

이 프로젝트는 [Contributor Covenant](CODE_OF_CONDUCT.md)를 따릅니다.

전체 문서의 목적과 권장 읽기 순서는 [문서 안내](docs/README.md)를 참고하세요.
