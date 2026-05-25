# 운영 규칙 — 통계 소스의 함정과 대응

DBTower의 모든 분석(load% 랭킹·시점 비교·회귀 감지)은 기종별 통계 소스 위에 서 있다.
그 소스 자체가 갖는 한계를 모르면 "통계가 안 보인다 / 수치가 이상하다"를 플랫폼 버그로
오인하게 된다. 여기 규칙들은 당근 DB 밋업(KDMS)에서 공유된 실운영 사례와
이 프로젝트의 실측(VERIFICATION.md)을 근거로 한다.

## 1. MySQL digest 테이블 가득참 — 새 쿼리가 통계에서 사라진다

`events_statements_summary_by_digest`는 `performance_schema_digests_size`
(기본 10,000)행까지만 저장한다. 가득 차면 **새로 유입된 쿼리의 통계가 별도 행으로
쌓이지 못하고** digest NULL의 잡동사니 행으로 합산된다 — 시점 비교의 핵심 기능인
"신규 쿼리 감지"가 정확히 이 지점에서 눈이 먼다.

운영 규칙 (KDMS 사례와 동일):

- 파라미터를 10,000 -> 20,000으로 상향
- 사용률이 80%를 넘으면 `TRUNCATE TABLE performance_schema.events_statements_summary_by_digest`

사용률 점검 쿼리:

```sql
SELECT (SELECT COUNT(*) FROM performance_schema.events_statements_summary_by_digest)
       / @@performance_schema.digests_size * 100 AS used_pct;
```

Truncate는 누적 카운터를 0으로 리셋한다 — DBTower의 시점 비교는 카운터 감소를
0으로 클램프하도록 설계되어 있어(ComparisonServiceTest의 카운터 리셋 케이스),
Truncate 직후 구간이 음수 발생량 같은 허위 수치로 나타나지 않는다. 리셋 직후 첫 구간의
쿼리들은 "구간 중간에 처음 나타난 쿼리" 규칙으로 처리된다.

참고: PostgreSQL은 같은 이슈가 없다. pg_stat_statements는 파싱 결과 기반 digest라
`pg_stat_statements.max`(기본 5,000)를 넘으면 덜 쓰인 항목을 자동 퇴출하고 신규를
저장한다 — 별도 운영 규칙이 필요 없다. 같은 "쿼리 통계"인데 저장소 관리 정책까지
기종마다 다르다는 것도 추상화가 필요한 근거다.

## 2. digest 길이 — 긴 쿼리가 서로 합쳐진다 (실측 완료)

digest는 쿼리 전체가 아니라 `max_digest_length`(기본 1024 byte)까지만 보고 만든다.
앞부분이 같은 긴 쿼리들이 하나의 digest로 병합되어 문제 쿼리를 특정할 수 없게 된다.
이 프로젝트에서 side-by-side로 재현·해소했다 (VERIFICATION 10절):
`max_digest_length` / `performance_schema_max_digest_length` 1024 -> 4096.

메모리 영향은 KDMS 발표의 계산 방식 그대로 — 세션당 1회 할당이라 동시 세션 1,000 기준
약 3MB, digest 테이블 10,000행의 DIGEST_TEXT 증가분 합계 약 90MB 수준이라 부담이 작다.

## 3. Prepared Statement와 통계 가시성 — 가급적 쓰지 않는 방향으로 가이드

KDMS 실사례: MySQL에서 PS(바이너리 프로토콜)를 쓰면 AWS Performance Insights에서
SQL_ID가 digest hash가 아닌 `PI-` prefix로 표시되고 QPS/Latency 통계가 아예 보이지 않았다.
통계 기반 운영 도구 전체가 그 쿼리에 대해 눈이 머는 것이다.

구조적 배경까지 알아야 판단이 선다:

- MySQL/PostgreSQL의 PS는 **세션(커넥션) 로컬**이다 — 다른 세션이 재사용할 수 없다
  (Oracle/SQL Server는 인스턴스 단위 공유 캐시)
- 그래서 커넥션 풀 환경에서는 커넥션 수 x 쿼리 수만큼 서버 메모리에 중복 캐싱된다
  (10 파드 x 50 커넥션 x 쿼리 1개 = 같은 플랜 500번 캐싱)
- 매 요청마다 prepare -> execute -> close를 반복하는 패턴이라면 왕복만 늘어 오히려 비효율

PS가 효율적인 조건은 좁다: 한 커넥션에서 prepare 1회 -> execute N회 -> close,
즉 커넥션 수가 적고 쿼리 패턴이 단조로운 배치성 워크로드. 일반 웹 워크로드에서는
통계 가시성을 지키는 쪽(텍스트 프로토콜)이 낫다는 것이 KDMS의 운영 결론이고,
DBTower도 같은 가이드를 따른다.

## 4. AAS와 load% — 같은 정보의 두 시선

AWS Performance Insights의 핵심 지표 AAS(Average Active Sessions)는
"일정 기간의 평균 동시 실행 세션 수"다. 1분 동안 어떤 쿼리를 수행한 세션 시간의 합이
120초면 AAS = 120/60 = 2 — 즉 **구간의 총 DB 시간 / 구간 길이**다.

DBTower의 load%는 같은 분자를 다른 분모로 나눈 것이다:

```
AAS      = 구간 총 실행 시간 / 구간 길이          (절대 부하 — 코어 수와 비교해 포화 판단)
load%    = 쿼리별 실행 시간 / 구간 총 실행 시간    (상대 기여 — 어떤 쿼리가 범인인지 판단)
```

포화 여부("DB가 버거운가")는 AAS를 코어 수와 비교해야 하지만, 원인 쿼리 랭킹("누가
범인인가")은 load%로 충분하다 — KDMS도 Top Query 화면의 첫 컬럼을 Load(AAS 기여)로
쓴다. 그리고 상위 랭킹만으로는 부족하다는 것(평소에도 높던 쿼리일 수 있다)이
시점 비교가 존재하는 이유다.

## 5. MongoDB system.profile — 누적 카운터가 아니다

다른 기종의 통계 소스는 서버 기동 이후 무한 누적 카운터지만, `system.profile`은
**capped collection**이다(기본 1MB). 가득 차면 오래된 문서부터 덮어쓰므로 집계 합이
줄어들 수 있다 — 시점 비교의 음수 클램프가 이를 카운터 리셋과 같은 방식으로 흡수하지만,
부하가 높은 인스턴스라면 프로파일 컬렉션 크기를 키워 덮어쓰기 주기를 늦추는 것이 좋다:

```javascript
// 프로파일러 정지 -> 컬렉션 재생성(크기 상향) -> 재개
db.setProfilingLevel(0)
db.system.profile.drop()
db.createCollection("system.profile", { capped: true, size: 64 * 1024 * 1024 })
db.setProfilingLevel(2)
```

프로파일러 레벨 2(모든 연산 기록)는 오버헤드가 있으므로 운영에서는 레벨 1 + slowms
임계로 낮추는 것이 일반적이다 — 이 경우 "느린 쿼리만" 통계에 잡힌다는 가시성
트레이드오프를 받아들이는 것이다 (PS 이슈와 같은 종류의 판단).
