# 대량 일괄 변경 — 설계와 안전 기준

승인된 티켓으로 **수십만~수백만 행**을 고치는 실행 방식을 정한다. 지금의 변경 실행(행 사본 방식)을 키우는 대신
별도 방식을 두는 이유와, 되돌리기를 무엇으로 보장하는지가 이 문서의 핵심이다.

관련 이슈: #101(이 명세)·#102(실행기)·#103(실행 전 조건·티켓)·#104(진행률·화면)·#105(100만 행 실측).

## 왜 지금 방식을 키우지 않는가

현재 `ChangeExecutionService`는 변경 전 행을 사본으로 떠 두고(`dbtower.workbench.change.max-rows`, 기본 10,000행)
영향 행 수를 대조한 뒤 커밋하며, 되돌리기는 그 사본으로 만든 역문장으로 한다. 이 방식의 안전은 **한 트랜잭션**에서 나오고,
그 대가가 행 수에 비례한다. 실측(`docs/experiments/change-lock-modes.md`, 2026-09-17):

| 기종 | 대상 행 | 방식 | 락 보유 중앙값 | 동시 작성자 p95 |
|---|---|---|---|---|
| PostgreSQL | 1,000 | PESSIMISTIC | 10.2ms | 1.0ms |
| PostgreSQL | 10,000 | PESSIMISTIC | 67.1ms | 1.1ms |
| PostgreSQL | 10,000 (작성자 쉼 50ms) | PESSIMISTIC | 81.8ms | 69.4ms |
| MySQL | 10,000 | PESSIMISTIC | 75.2ms | 1.8ms |
| MySQL | 10,000 (작성자 쉼 50ms) | PESSIMISTIC | 94.9ms | 86.6ms |

행 10배에 락 보유가 6~7배로 늘고, 동시 쓰기가 있으면 남의 쓰기 p95가 69~87ms까지 밀린다. 100만 행이면 같은 방식으로
수 초~수십 초를 한 트랜잭션이 쥔다. 게다가 한 트랜잭션의 대량 변경은 복제를 한꺼번에 밀어(binlog·WAL 한 덩이)
읽기 복제본이 뒤처진다. 그래서 대량은 **잘게 쪼개 커밋하는 별도 실행기**로 간다.

되돌리기도 같은 이유로 방식이 달라진다. 100만 행의 사본을 플랫폼에 저장하는 것은 되돌리기 보장이 아니라
또 하나의 데이터 사본 관리 문제다. 대량 변경의 되돌리기는 **복원 검증에 성공한 최근 백업**으로 보장한다.

## 실행 방식

### 기본 키 범위 배치

대상을 `WHERE` 조건으로 한 번에 고치지 않고, 기본 키 구간으로 나눠 구간마다 따로 커밋한다.

```sql
-- 배치 하나 (키가 비연속이어도 누락·중복이 없다)
UPDATE t SET ... WHERE <원래 조건> AND id > :lastKey AND id <= :nextKey
```

- `nextKey`는 행 수를 세지 않고 키를 훑어 정한다 — `SELECT id FROM t WHERE id > :lastKey ORDER BY id LIMIT 1 OFFSET :batchSize`가 없으면
  마지막 키를 끝으로 본다. 빈 구간은 문장을 보내지 않고 건너뛴다
- 조건은 승인된 문장의 `WHERE`를 그대로 두고 키 범위만 `AND`로 덧붙인다 — 파서가 조건을 다시 쓰지 않는다
- 복합 키는 사전순 비교(`(a, b) > (:a, :b)`)로 같은 성질을 유지한다. 기종별 문법 차이는 Dialect가 흡수한다
- 키가 없는 테이블(PK 없음)은 **거부한다** — 배치 경계를 정할 수 없으면 누락·중복을 막을 수 없다

### 배치 크기와 쉬는 간격

기본값은 배치 1,000행 · 쉼 100ms로 두고 티켓에 고정한다. 근거는 위 표다 — 1,000행 구간의 락 보유가 10~14ms이고,
그 정도면 동시 작성자 p95가 1ms대에 머문다. 쉼은 복제·purge가 따라오는 시간을 준다. 두 값 모두 승인 대상에 넣어
실행자가 승인 뒤에 바꿀 수 없게 한다(#103).

### 복제 지연 감시와 자동 멈춤·재개

배치마다 `DbmsOperator.replicationState()`를 읽는다.

- `lagSource == MEASURED`이고 `lagSeconds`가 임계(기본 5초)를 넘으면 **PAUSED_LAG**로 멈춘다. 임계 아래로 내려오면 재개한다
- `lagSource != MEASURED`면(못 재는 기종·지금 못 읽음) 지연으로 멈추지 않고, 그 사실을 배치 기록에 남긴다.
  "못 잰다"와 "지연 0"을 같은 값으로 쓰지 않는다(`ReplicationState` 주석의 규율)
- 복제가 끊긴 신호(MySQL `Seconds_Behind_Source` NULL)는 지연이 아니라 **중단**으로 본다 — 사람이 재개해야 한다

## 상태 전이

승인 티켓의 권위는 그대로 `ChangeTicketGate`에 둔다(`APPROVED -> EXECUTING -> EXECUTED`). 대량 실행은 그 안에서
배치 진행 상태를 따로 들고, 실행권은 조건부 UPDATE로만 얻는다.

```text
APPROVED -> EXECUTING -> EXECUTED            정상 완주
EXECUTING -> PAUSED_LAG -> EXECUTING         복제 지연으로 멈췄다 재개
EXECUTING -> PAUSED_BY_USER -> EXECUTING     사람이 멈췄다 재개
EXECUTING -> CANCELLED                       사람이 취소. 커밋된 배치는 남는다(아래 참조)
EXECUTING -> FAILED                          배치가 실패해 더 진행하지 않는다
```

**취소·실패는 이미 커밋한 배치를 자동으로 되돌리지 않는다.** 대량 변경은 부분 적용이 정상 상태다. 취소하면
"어디까지 적용됐는가"를 마지막 키로 남기고, 되돌리려면 사람이 백업 복원 경로로 판단한다. 조용히 역문장을 돌려
절반만 되돌리는 쪽이 더 위험하다.

## 실행 전 조건 (하나라도 어기면 거부)

1. **복원 검증에 성공한 최근 백업** — `BackupFreshness.verifyStatus == "VERIFIED"`이고 `status == FRESH`.
   `UNSUPPORTED`·`FAILED`·`NO_BACKUP`·`STALE`는 거부한다. 되돌리기를 백업에 맡기므로 이것이 이 기능의 전제다
2. **기본 키가 있는 단일 테이블** — 조인 대상 변경, PK 없는 테이블은 거부
3. **지원 문장** — `UPDATE`, `DELETE`, `INSERT ... SELECT`. 그 밖은 거부
4. **지원 기종** — MySQL·PostgreSQL 먼저. 나머지는 Operator에 능력이 붙을 때까지 거부
5. **승인 시점에 고정된 값** — 예상 영향 행 수, 배치 크기, 쉬는 간격, 복제 지연 임계. 승인 뒤 변경 불가
6. **예상 영향 행 수와 실제의 괴리** — 실행 시작 시 다시 센 수가 승인 시점의 2배를 넘으면 멈추고 재승인을 요구한다

## 권한

| 동작 | 역할 |
|---|---|
| 티켓 작성·예상 조회 | 운영자 |
| 승인 | 승인자(작성자와 달라야 한다) |
| 실행 시작 | 운영자 |
| 일시정지·재개 | 운영자 |
| 취소 | 운영자 또는 승인자 |

## 기록

배치마다 처리한 키 범위(시작·끝), 영향 행 수, 걸린 시간, 그 시점의 복제 지연과 `lagSource`를 남긴다(#104).
감사에는 실행 시작·멈춤·재개·취소·완료를 사람과 시각과 함께 남긴다. 실행 중 화면은 이 기록을 읽어 진행률을 만든다.

## 현업 참고

- Alibaba Cloud DMS "Change data without locking tables" — 대량 변경을 배치로 쪼개고 사이에 쉼을 두는 방식:
  https://www.alibabacloud.com/help/en/dms/user-guide/change-data-without-locking-tables
- Percona Toolkit `pt-archiver` — 키 범위로 나눠 반복 처리하고 복제 지연을 보고 멈추는 `--max-lag`:
  https://docs.percona.com/percona-toolkit/pt-archiver.html
- GitHub `gh-ost` — 복제 지연 임계로 스로틀하는 온라인 스키마 변경. 이 저장소는 스키마 변경에 이미 gh-ost를 쓴다(onlineddl 모듈):
  https://github.com/github/gh-ost/blob/master/doc/throttle.md

세 도구가 공통으로 쓰는 것이 (1) 키 범위 배치, (2) 배치 사이 쉼, (3) 복제 지연 스로틀이다. 이 명세도 같은 세 가지를 쓴다.
다른 점은 되돌리기다 — 위 도구들은 되돌리기를 사용자에게 맡기지만, 이 플랫폼은 승인 게이트를 가지므로
**복원 검증된 백업을 실행 전 조건으로 걸어** 되돌릴 자리가 있을 때만 실행한다.

## 이 명세가 정하지 않은 것

- MongoDB·Oracle·SQL Server의 대량 변경. Operator 능력으로 흡수할 수 있는지부터 검토한다
- 여러 테이블에 걸친 변경(조인·서브쿼리 갱신)
- 배치 크기 자동 조정(적응형). 먼저 고정값으로 실측하고(#105) 필요가 보이면 다음에 본다
