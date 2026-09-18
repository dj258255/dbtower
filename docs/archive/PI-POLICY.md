# Pi 실행 정책

DBTower는 모델에게 `운영 배포하지 마`라고 부탁하는 것만으로 안전하다고 보지 않습니다. 로컬 코딩 에이전트인 Pi의 `tool_call` 확장에서 파일 경로·셸 명령·승인·테스트 상태를 다시 검사하고, DBTower 서버의 인증·읽기 전용·변경 티켓 게이트를 최종 권위로 둡니다.

## 실행

Pi가 설치된 환경에서 저장소 루트에서 실행합니다.

```bash
pi --extension .pi/extensions/dbtower-policy.ts --dbtower-policy readonly
DBTOWER_PI_APPROVAL_ID=DBT-42 pi --extension .pi/extensions/dbtower-policy.ts --dbtower-policy change
```

`readonly`는 읽기 도구만 허용합니다. `change`는 작업공간 안의 `edit`·`write`와 허용 목록의 검증 명령을 열지만, 파일 변경에는 사람이 시작할 때 지정한 `DBTOWER_PI_APPROVAL_ID`가 필요합니다. Pi가 셸 안에서 환경변수를 바꿔도 이미 시작한 확장의 승인값은 바뀌지 않습니다.

## 차단 기준

- 작업공간 밖, `.git`, `.env`, `backups`, `build`, `data` 경로 읽기·수정
- 셸 연결자·리다이렉션·명령 치환
- `git push/reset/clean/checkout/commit`, `kubectl`, `helm`, `terraform apply/destroy`, Docker Compose 기동·삭제
- 직접적인 운영 DB 클라이언트와 DDL/DML 명령
- 정책에 등록되지 않은 도구

파일 변경 뒤 성공한 `test`·`check`·`verify` 명령이 없으면 다음 에이전트 턴을 검증 요청으로 되돌립니다. 모든 허용·차단·테스트·완료 게이트는 `.pi/policy-audit.ndjson`에 남지만, 이 파일은 비밀값이 섞일 수 있어 Git에 커밋하지 않습니다.

## 왜 DBTower 서버 게이트도 필요한가

Pi 확장은 사용자의 로컬 프로세스 안에서 실행되며 확장 자체가 전체 시스템 권한을 가집니다. 따라서 악성 확장이나 Pi를 거치지 않은 curl·MCP 호출까지 막을 수 없습니다. 실제 대상 DB 변경은 DBTower의 요청자→승인자→운영자 전이, 조회 계정·읽기 전용 트랜잭션, 영향 행과 사본 대조, 마스킹·감사 경계를 반드시 통과해야 합니다. Pi는 모델이 우회하기 쉬운 로컬 도구 호출을 먼저 줄이는 보조 방어선입니다.

네트워크가 필요한 `pi-web-access` 같은 확장은 이 정책의 필수 구성으로 자동 설치하지 않습니다. 확장은 전체 시스템 권한으로 실행될 수 있으므로 출처·코드·권한을 검토한 뒤 별도로 선택해야 합니다. DBTower 진단은 읽기 전용 MCP/REST 경계를 통해서도 동작하므로, 인터넷 접근을 기본값으로 열 필요가 없습니다.

## 로컬 검증

```bash
node scripts/test-pi-policy.mjs
```

이 테스트는 경로 이탈, 보호 파일, 승인 없는 수정, 허용된 테스트, `git push`·`kubectl`·셸 연결자·줄바꿈·절대/상위 경로·미등록 명령 차단을 순수 정책 함수로 확인합니다. Pi 런타임 자체의 UI와 모델 공급자 호출은 포함하지 않습니다.
