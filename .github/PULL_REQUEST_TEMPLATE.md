## 무엇을 왜 바꿨나
<!-- 변경의 의도. 닫는 이슈: Closes #번호 -->

## 어떻게 검증했나
<!-- ./gradlew test 결과, 라이브 실측 명령·출력, 화면이면 브라우저에서 확인한 동작. 관련 docs/VERIFICATION.md 절 번호 -->

## 머지 순서
<!-- 다른 PR 위에 쌓았으면 적는다. 쌓인 PR은 head 브랜치를 바로 지우지 않는다(AGENTS.md "작업 기록") -->

## 체크리스트
- [ ] `./scripts/check-conventions.sh`·`./gradlew test` 통과 (ModularityTests 포함)
- [ ] 새 모듈 코드가 internal/ 규칙을 따름 (AGENTS.md)
- [ ] 화면이 바뀌었으면 실제 브라우저로 확인함
- [ ] 성능 주장은 before/after 실측과 함께
- [ ] 비밀값을 커밋하지 않음
- [ ] 라벨(type·area)·마일스톤, 이슈의 실제 마감을 채움
