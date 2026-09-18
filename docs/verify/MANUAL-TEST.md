# 역할별 수동 테스트

이 문서는 로컬에서 DBTower 화면을 직접 누르며 역할 분리와 변경 티켓 흐름을 확인하는
체크리스트입니다. 가장 중요한 확인은 다음 한 줄입니다.

```text
요청자는 요청만 -> 승인자는 승인만 -> 운영자는 실행과 되돌리기만
```

## 준비 결과

아래 준비를 마치면 <http://localhost:8080>에서 동일한 비밀번호로 다섯 계정을 사용할 수
있습니다.

| 사용자 | 역할 | 처음 확인할 화면 |
|---|---|---|
| `admin` | 관리자 | 관제와 사용자·역할 관리 |
| `viewer` | 관제 | 관제 화면만 |
| `requester` | 요청자 | 워크벤치와 변경 요청 |
| `approver` | 승인자 | 변경 티켓 승인·반려 |
| `operator` | 운영자 | 승인 티켓 실행·되돌리기 |

로컬 데모 비밀번호는 실행할 때 `DBTOWER_*_PASSWORD` 환경변수로 지정합니다. 운영 환경에
예제 비밀번호를 사용하지 않습니다.

## 1. 실행

개발용 대상 DB와 모니터링 스택을 먼저 실행합니다.

```bash
docker compose up -d
```

fresh volume에는 모니터 계정만 자동으로 생깁니다. 워크벤치용 데이터와 READ·WRITE 계정은
아래 멱등 스크립트로 준비합니다. 여러 번 실행해도 같은 상태가 됩니다.

```bash
docker exec -i dbtower-postgres psql -U postgres -d sample < docker/workbench-postgres.sql
```

기존 개발 데이터를 보존하면서 독립적으로 시험하려면 별도의 메타 DB를 사용합니다.

```bash
docker exec dbtower-postgres createdb -U postgres dbtower_clicktest
```

이미 데이터베이스가 있다는 오류는 무시해도 됩니다. 이어서 앱을 실행합니다.

먼저 임시 파일에 이번 실행에서만 쓸 값을 만듭니다. 파일 권한은 현재 사용자만 읽을 수
있게 두며, 테스트가 끝나면 삭제합니다. 대상 DB 비밀번호는 실행 중인 개발 컨테이너에서
읽어 오므로 저장소 문서에 고정하지 않습니다.

```bash
umask 077
export DEMO_ENV=/tmp/dbtower-manual.env
export DEMO_PASSWORD="$(openssl rand -base64 24)"
export DEMO_TOKEN="$(openssl rand -hex 32)"
export LOCAL_DB_PASSWORD="$(docker inspect dbtower-postgres \
  --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^POSTGRES_PASSWORD=//p' | head -1)"
printf 'export DEMO_PASSWORD=%q\nexport DEMO_TOKEN=%q\nexport LOCAL_DB_PASSWORD=%q\n' \
  "$DEMO_PASSWORD" "$DEMO_TOKEN" "$LOCAL_DB_PASSWORD" > "$DEMO_ENV"
```

같은 터미널에서 앱을 실행합니다.

```bash
SPRING_PROFILES_ACTIVE=dev \
DBTOWER_DB_URL='jdbc:postgresql://127.0.0.1:15432/dbtower_clicktest?reWriteBatchedInserts=true' \
DBTOWER_DB_USERNAME=postgres \
DBTOWER_DB_PASSWORD="$LOCAL_DB_PASSWORD" \
DBTOWER_ADMIN_PASSWORD="$DEMO_PASSWORD" \
DBTOWER_VIEWER_PASSWORD="$DEMO_PASSWORD" \
DBTOWER_REQUESTER_PASSWORD="$DEMO_PASSWORD" \
DBTOWER_APPROVER_PASSWORD="$DEMO_PASSWORD" \
DBTOWER_OPERATOR_PASSWORD="$DEMO_PASSWORD" \
DBTOWER_API_TOKEN="$DEMO_TOKEN" \
DBTOWER_WEBHOOK_URL='' \
./gradlew bootRun
```

`dev` 프로필은 로컬 테스트 편의를 위해 암호화 키 없이 실행할 수 있습니다. 운영 배포에서는
반드시 `DBTOWER_ENCRYPTION_KEY`를 사용합니다.

## 2. 테스트 대상 등록

처음 만든 `dbtower_clicktest`에는 관리 대상이 없습니다. 앱을 켜 둔 채 새 터미널에서
PostgreSQL 대상과 워크벤치용 READ·WRITE 계정을 등록합니다. 비밀번호는 명령 인자가 아니라
표준입력의 JSON으로 전달합니다.

새 터미널은 앞에서 만든 임시 환경 파일을 먼저 읽습니다.

```bash
source /tmp/dbtower-manual.env

curl -X POST http://localhost:8080/api/instances \
  -H "Authorization: Bearer $DEMO_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{
  "name": "manual-postgres",
  "type": "POSTGRESQL",
  "host": "127.0.0.1",
  "port": 15432,
  "dbName": "sample",
  "username": "dbtower_monitor",
  "password": "$LOCAL_DB_PASSWORD"
}
JSON
```

응답의 `id`가 1이라면 다음 명령을 그대로 사용합니다. 다른 값이면 URL의 `1`을 그 값으로
바꿉니다.

```bash
curl -X PUT http://localhost:8080/api/instances/1/credentials/READ \
  -H "Authorization: Bearer $DEMO_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{"username":"dbtower_reader","password":"$LOCAL_DB_PASSWORD"}
JSON

curl -X PUT http://localhost:8080/api/instances/1/credentials/WRITE \
  -H "Authorization: Bearer $DEMO_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{"username":"dbtower_writer","password":"$LOCAL_DB_PASSWORD"}
JSON
```

앞 준비 명령을 실행하면 `customers` 3행과 `orders` 2,000행, 워크벤치용 계정이 준비됩니다.

## 3. 브라우저 준비

가장 간단한 방법은 한 브라우저에서 역할이 바뀔 때마다 로그아웃하는 것입니다. 여러 역할을
동시에 열려면 서로 다른 Chrome 프로필이나 서로 다른 브라우저를 사용합니다. 같은 Chrome의
시크릿 창 여러 개는 세션을 공유할 수 있으므로 역할 분리용으로 적합하지 않습니다.

각 계정으로 로그인한 뒤 우측 상단의 역할 배지가 예상 역할과 같은지 먼저 확인합니다.

## 4. 권한만 빠르게 확인

| 역할 | 보여야 하는 것 | 없어야 하는 것 |
|---|---|---|
| 관제 | 지표, 비교, 리포트 | 워크벤치 진입, 변경 요청 |
| 요청자 | 워크벤치 조회, 변경 요청 | 승인, 실행 |
| 승인자 | 변경 티켓, 드라이런, 승인·반려 | 실행 |
| 운영자 | 드라이런, 실행, 되돌리기 | 승인·반려 |
| 관리자 | 위 기능 전체, 사용자·역할, 감사 | 없음 |

버튼을 숨기는 것은 화면 편의일 뿐입니다. 직접 URL이나 API를 호출해도 서버가 권한을 다시
검사하며, 허용되지 않은 작업은 403이어야 합니다.

## 5. 한 티켓을 끝까지 테스트

대상은 `manual-postgres`, 테이블은 `customers`를 사용합니다.

### 요청자

1. `requester`로 로그인합니다.
2. 상단에서 `워크벤치`를 선택합니다.
3. 왼쪽 대상에서 `manual-postgres`를 선택합니다.
4. 아래 조회를 실행해 3번 고객의 현재 등급을 확인합니다.

```sql
SELECT id, name, grade
FROM customers
WHERE id = 3;
```

5. 아래 변경문을 입력합니다.

```sql
UPDATE customers
SET grade = 'BRONZE'
WHERE id = 3;
```

6. 즉시 실행되지 않고 변경 요청 창으로 연결되는지 확인한 뒤 요청을 제출합니다.
7. 요청자 화면에는 승인과 실행 버튼이 없어야 합니다.

### 승인자

1. 로그아웃하고 `approver`로 로그인합니다.
2. `워크벤치 -> 변경 티켓 -> 열린 것만`에서 방금 요청을 엽니다.
3. `드라이런`을 눌러 실제 실행 후 롤백된 결과와 예상 영향 행 수를 확인합니다.
4. 티켓을 승인합니다.
5. 승인 뒤에도 실행 버튼이 없어야 합니다.

### 운영자

1. 로그아웃하고 `operator`로 로그인합니다.
2. 같은 티켓을 열어 `실행`을 누릅니다.
3. 확인을 위해 버튼 문구가 바뀌면 한 번 더 누릅니다.
4. 실행 기록에서 요청자, 승인자, 실행자가 서로 다른지 확인합니다.
5. 같은 기록의 `되돌리기`로 3번 고객의 원래 등급을 복원합니다.

## 5-1. 대량 일괄 변경 (거부되는 것을 먼저 본다)

이 경로는 되돌리기를 행 사본이 아니라 **복원 검증된 최근 백업**에 겁니다. 그래서 정상 동작보다
**거부**를 먼저 확인하는 것이 이 기능의 요점입니다.

### 거부 확인 (백업 없이)

1. `requester`로 아래 변경문을 요청합니다.

```sql
UPDATE customers
SET note = 'bulk'
WHERE grade = 'BRONZE';
```

2. `approver`가 승인합니다.
3. `operator`로 티켓을 열면 `대량 일괄 실행(배치로 나눠)` 버튼이 보입니다. 누르고 한 번 더 눌러 확인합니다.
4. **실행되지 않고 거부돼야 합니다.** manual 대상에는 복원 검증된 백업이 없기 때문입니다.
   메시지가 "복원 검증에 성공한 백업이 없어 대량 변경을 실행하지 않습니다"인지 확인합니다.
5. 티켓 상태가 `승인`으로 그대로인지 확인합니다 — 거부된 티켓이 실행 중 상태에 갇히면 안 됩니다.

### 다른 거부도 한 번씩

같은 방식으로 아래를 각각 요청·승인해 거부 문구를 확인합니다. 백업 조건보다 먼저 걸리는 것도 있습니다.

| 변경문 | 기대 거부 |
|---|---|
| `DELETE FROM customers ORDER BY id LIMIT 10` | ORDER BY·LIMIT은 배치로 나누면 뜻이 달라집니다 |
| 기본 키가 없는 테이블의 UPDATE | 배치 경계를 정할 수 없습니다 |
| `INSERT INTO ... SELECT ...` | 단일 테이블 UPDATE·DELETE만 지원합니다 |

### 정상 실행 (백업 조건을 갖춘 대상에서만)

복원 검증에 성공한 백업이 있는 대상에서 같은 절차를 밟으면 진행 패널이 열립니다. 확인할 것은 셋입니다.

1. 배치 수와 바뀐 행이 늘어나고, `마지막 적용 키`가 갱신됩니다.
2. `일시정지`를 누르면 다음 배치가 시작되지 않고 상태가 `멈춤`이 됩니다. `재개`로 이어집니다.
3. `취소`하면 멈추되 **이미 커밋한 배치는 되돌아가지 않습니다**. 대상 DB에서 마지막 키까지만 바뀌었는지 직접 확인합니다.

배치 표의 `복제 지연` 열이 `못 읽음`·`복제 없음`으로 나오는지도 봅니다 — 여기에 `0초`가 찍히면 안 됩니다.

## 6. 관리자와 감사 기록

`admin`으로 로그인해 다음을 확인합니다.

- `사용자·역할` 카드에 다섯 계정과 역할이 표시됩니다.
- 역할은 표에서 고른 뒤 `[적용]`을 눌러야 반영됩니다(`[취소]`면 원래 값으로 돌아옵니다). 바뀐 권한은 그 사용자의 다음 로그인부터 적용됩니다.
- 감사 로그에 요청, 드라이런, 승인, 실행, 되돌리기의 실제 사용자가 남습니다.
- 승인자와 운영자는 서로의 역할을 포함하지 않습니다.

## 문제가 생기면

| 증상 | 확인할 것 |
|---|---|
| 로그인 실패 | 사용자 이름과 실행 시 지정한 `DEMO_PASSWORD` 확인 |
| `manual-postgres`가 없음 | 앱 기동 뒤 테스트 대상 등록 단계가 완료됐는지 확인 |
| 워크벤치에 조회 계정 없음 | 대상의 `READ` 콘솔 계정 등록 여부 확인 |
| 실행 시 변경 계정 없음 | 대상의 `WRITE` 콘솔 계정 등록 여부 확인 |
| 쿼리 표가 비어 있음 | 대상 DB에 부하가 없을 수 있으므로 위 `customers` 조회부터 실행 |
| AI 분석이 비활성 | `ANTHROPIC_API_KEY`가 없으면 정상이며 규칙 분석은 계속 동작 |

## 7. 정리

앱을 `Ctrl-C`로 끈 뒤, 독립 시험용 메타 DB와 임시 환경 파일을 지웁니다. 관리 대상의
`customers` 데이터와 계정은 저장소 데모 환경이 함께 쓰므로 그대로 둡니다.

```bash
docker exec dbtower-postgres dropdb -U postgres --if-exists dbtower_clicktest
rm -f /tmp/dbtower-manual.env
```

발표처럼 5분 안에 보여 주는 순서는 [DEMO-5MIN.md](../portfolio/DEMO-5MIN.md), 각 역할의 권한을
서버 수준에서 검증한 근거는 [VERIFICATION.md](VERIFICATION.md) 135~136절에 있습니다.
