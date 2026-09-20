# E6b: 배치 사이 쉼이 복제 지연을 줄이는가

이 문서와 `replication-pause.csv`는 `ReplicationPauseExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.

- 실행 일시: 2026-09-21T00:36:15.941366+09:00
- 대상: PostgreSQL 16 primary(`e6-pg-primary`, 16432) + streaming replica(`e6-pg-replica`, 16433, async)
- 변경: `bulk_scale` 1000000행을 1,000행 배치로 `note` 갱신. 배치 사이 쉼만 바꾼다(0 / 100 / 300ms)
- 동시 작성자 2개가 범위 안 임의 행을 쉬지 않고 고친다. 표의 writer 수치는 그 커밋들의 소요다
- replica lag: 배치마다 primary의 `pg_stat_replication.replay_lag`를 읽는다(조건 시작 전 따라잡기를 기다린다)
- 한계: 쉼 동안에도 동시 작성자가 계속 쓴다. 그래서 쉼은 primary를 쉬게 하는 것이 아니라 다른 writer에게 시간을 주는 것이다. 쉼 자체의 효과만 분리하려면 writer를 멈춘 조건이 따로 필요하다

| 쉼 | 총 소요 | 배치 수 | 바뀐 행 | replay lag 최대 | replay lag 평균 | 표본 | writer p50 | writer p95 | writer 최대 | writer 커밋 수 |
|---|---|---|---|---|---|---|---|---|---|---|
| 0ms | 11.1초 | 1000 | 1000000 | 488ms | 14ms | 1000 | 2.38ms | 7.65ms | 70.84ms | 6730 |
| 100ms | 123.2초 | 1000 | 1000000 | 345ms | 4ms | 1000 | 1.42ms | 4.70ms | 261.51ms | 119059 |
| 300ms | 351.6초 | 1000 | 1000000 | 896ms | 16ms | 1000 | 1.11ms | 5.48ms | 289.28ms | 327707 |
