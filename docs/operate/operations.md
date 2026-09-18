# 운영 규칙 — 통계 소스의 함정과 대응

DBTower의 모든 분석(load% 랭킹·시점 비교·회귀 감지)은 기종별 통계 소스 위에 서 있다.
그 소스 자체가 갖는 한계를 모르면 "통계가 안 보인다 / 수치가 이상하다"를 플랫폼 버그로
오인하게 된다. 여기 규칙들은 한 DB 밋업(레퍼런스)에서 공유된 실운영 사례와
이 프로젝트의 실측(VERIFICATION.md)을 근거로 한다.

## 1. MySQL digest 테이블 가득참 — 새 쿼리가 통계에서 사라진다

`events_statements_summary_by_digest`는 `performance_schema_digests_size`
(기본 10,000)행까지만 저장한다. 가득 차면 **새로 유입된 쿼리의 통계가 별도 행으로
쌓이지 못하고** digest NULL의 잡동사니 행으로 합산된다 — 시점 비교의 핵심 기능인
"신규 쿼리 감지"가 정확히 이 지점에서 눈이 먼다.

운영 규칙 (레퍼런스 사례와 동일):

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

메모리 영향은 레퍼런스 발표의 계산 방식 그대로 — 세션당 1회 할당이라 동시 세션 1,000 기준
약 3MB, digest 테이블 10,000행의 DIGEST_TEXT 증가분 합계 약 90MB 수준이라 부담이 작다.

## 3. Prepared Statement와 통계 가시성 — 가급적 쓰지 않는 방향으로 가이드

레퍼런스 실사례: MySQL에서 PS(바이너리 프로토콜)를 쓰면 AWS Performance Insights에서
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
통계 가시성을 지키는 쪽(텍스트 프로토콜)이 낫다는 것이 레퍼런스의 운영 결론이고,
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
범인인가")은 load%로 충분하다 — 레퍼런스도 Top Query 화면의 첫 컬럼을 Load(AAS 기여)로
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

## lakehouse 연동 운영 (Phase 5 — 두 저장소 루프)

### 계정·권한 (환경 소유 — 마이그레이션에 없음)

- `lakehouse_reader` — 원천 5테이블 SELECT(정확한 GRANT는 lakehouse CONTRACT §1-1).
- `lakehouse_writer` — `baseline_longterm` 한 테이블만 SELECT/INSERT/DELETE.
- **함정(실측)**: Flyway가 테이블을 DROP·재생성하면 GRANT가 함께 사라진다 — V24류
  마이그레이션 후 첫 되쓰기가 permission denied면 이것이다. DDL 재생성 시 GRANT 재적용이
  운영 절차다(VERIFICATION 100-1).

### 자연어 서빙(MCP lakehouse 도구) 환경변수

셀프호스트/개발 실행 설정에 셋을 넣어야 `lakehouse_query`·`lakehouse_card_create`가 켜진다
(미설정이면 REST가 404 — 기능 게이트, 있는 척하지 않음):

```
DBTOWER_METABASE_URL=http://<metabase-host>:<port>
DBTOWER_METABASE_API_KEY=...            # 권장(x-api-key)
# 또는 관리자 세션 경로:
DBTOWER_METABASE_EMAIL=... / DBTOWER_METABASE_PASSWORD=...
# duckdb 커넥션이 여럿이면 명시: DBTOWER_METABASE_DATABASE_ID=<id>
```

에이전트 생성 카드는 "DBTower AI" 컬렉션에 격리된다 — 사람 대시보드와 섞이지 않는다.

### 공급 잡 3종 요약 (주기·보존 전부 프로퍼티로 조정 가능)

| 잡 | 주기(기본) | 보존 | lakehouse 소비 |
|---|---|---|---|
| WaitEventSnapshotJob | 5분 | 7일 | fct_wait_event_daily·mart_wait_top |
| SizeSnapshotJob(+volumeStat) | 6시간 | 7일 | fct_size_daily·mart_capacity_forecast |
| (기존) SnapshotScheduler | 60초 | 7일 | 주 파이프라인 전체 |

volume 판정(임계 원천 ②): MSSQL=dm_os_volume_stats(총량/여유, 실측 1007GB/774GB),
Oracle=dba_data_files(할당/autoextend 상한, 실측 1189MB/96TB), MySQL·PG·Mongo=NULL(불가 정직).

---

## 6. ASH 샘플러 운영 (V32) — 켤 때 알아야 할 것

### 기본은 꺼져 있다

`dbtower.ash.enabled=false`가 기본이라 켜지 않으면 빈 자체가 뜨지 않는다. **롤백은 이 한 줄이다.**

```yaml
dbtower:
  ash:
    enabled: false            # 기본 꺼둠
    interval-ms: 1000         # 샘플 주기. 락은 초 단위로 생겼다 사라진다
    max-sessions-per-tick: 500
    workers: 4
    retention-days: 7         # query_snapshot·wait_event_snapshot과 대칭
    retention-sweep-ms: 3600000
```

### 켜기 전 확인할 셋

1. **대상은 현재 PostgreSQL만이다.** `DbmsType.POSTGRESQL`이 아니면 건너뛴다
2. **`max_connections`가 먼저 벽이다.** 인스턴스마다 커넥션 풀이 생기므로, 관리 대상이 많으면
   샘플러가 아니라 대상 DB의 커넥션 한도가 먼저 찬다. 실측에서 60인스턴스 + 부하 35세션으로
   기본값 100을 넘겼다
3. **부피가 다른 스냅샷보다 훨씬 빨리 는다.** 실측에서 77,104 샘플에 23MB였다. 보존 7일과
   스윕 주기를 반드시 확인하라

### 백프레셔 정책: 쌓지 않고 떨어뜨린다

직전 틱이 아직 안 끝났으면 이번 틱을 **버리고** `SKIPPED_INFLIGHT`로 기록한다. 큐에 쌓으면
관측이 대상을 더 느리게 만들고, 정확히 락 폭풍 순간에 커넥션을 먹는다. Oracle ASH도 AWS RDS
Performance Insights도 부하가 높으면 샘플을 떨어뜨리는 쪽을 골랐다.

세션이 `max-sessions-per-tick`을 넘으면 `elapsed_ms` 내림차순으로 자른다(오래 걸린 세션이 진단
가치가 높다 — Datadog이 느리거나 잦은 쿼리로 편향 샘플링하는 것과 같은 선택). 잘라낸 수는
`dropped_sessions`에 남긴다. **떨어뜨린 사실을 숨기지 않는다.**

### 0을 읽는 법

`ash_sample`에 행이 없다고 "활성 세션이 없었다"가 아니다. **반드시 `ash_sample_tick`을 같이
보라.** 틱이 있고 `observed_sessions=0`이면 진짜 0건이고, 틱 자체가 없으면 샘플러가 안 돈 것이다.

결번 판정은 `(sampler_run_id, instance_id)` 안에서만 유효하다. 샘플러 JVM이 재기동하면
`sample_seq`가 1부터 다시 시작하므로, run을 넘어 이으면 없는 결측이 생긴다.

### 보존 스윕이 대량 DELETE를 피하는 이유

`ctid` 서브쿼리로 5만 행씩 끊어 지운다. 한 트랜잭션이 테이블 전체를 잠그면 관측 정리가 관측
대상을 흔드는 자기모순이 된다.

### 알려진 한계 (정직 표기)

- **샘플 간격보다 짧은 대기는 놓친다.** 외부 폴링 샘플링의 원리적 한계다. pgsentinel 같은
  인프로세스 확장이 정석이지만 `shared_preload_libraries` 등록과 재시작을 강제해야 해서
  밖에서 붙는 도구로는 채택할 수 없었다(Datadog·SolarWinds DPA와 같은 선택)
- `wait_category`는 이벤트 이름으로 **근사**한다. 정확한 분류는 `SessionInfo`에 카테고리를
  추가해야 하고, 그건 5기종 공통 모델 변경이라 이 아크의 범위를 넘는다

실측 근거는 `docs/verify/VERIFICATION.md` §124, 계측 절차는 `dbtower-lakehouse/docs/RUNBOOK.md` §8.

## 7. 정식 이미지 배포와 업그레이드

정식 배포물은 `vX.Y.Z` 태그에서 만든 `ghcr.io/dj258255/dbtower` 멀티아치 이미지다.
릴리즈 워크플로는 규약 검사, Playwright Chromium 역할별 E2E, 전체 테스트를 통과한 뒤
`X.Y.Z`, `X.Y`, `X`, `latest` 태그와 GitHub Release를 함께 게시한다. 현재 정식 버전은
`v1.4.0`이다.

운영에서는 `latest`를 그대로 추적하지 말고 `.env`의 `DBTOWER_TAG`를 정식 버전으로 고정한다.
메타 DB와 백업 볼륨을 보존한 채 앱 이미지만 교체하는 기본 순서는 다음과 같다.

```bash
# 먼저 메타 DB를 백업하고, 기존 DBTOWER_ENCRYPTION_KEY가 보존됐는지 확인한다.
DBTOWER_TAG=1.4.0 docker compose -f docker-compose.app.yml pull dbtower
DBTOWER_TAG=1.4.0 docker compose -f docker-compose.app.yml up -d dbtower
docker compose -f docker-compose.app.yml ps
curl -fsS http://localhost:${DBTOWER_PORT:-8080}/actuator/health
```

앱 기동 시 Flyway가 메타 DB 스키마를 검증·마이그레이션한다. 실패하면 새 버전의 앱을 계속
재시작하지 말고 로그와 메타 DB 백업을 확인한다. `DBTOWER_ENCRYPTION_KEY`를 바꾸거나 잃으면
기존에 저장한 대상 DB 자격증명을 복호화할 수 없으므로 이미지 교체와 별개로 같은 키를 유지해야 한다.

이 저장소가 자동 게시하는 범위는 GitHub Release와 GHCR 이미지까지다. 특정 조직의 서버,
Kubernetes 클러스터, RDS에는 자격증명과 비용 권한 없이 자동 배포하지 않는다. 런타임 반영은
이 compose 절차나 해당 조직의 배포 시스템에서 명시적으로 수행한다.
