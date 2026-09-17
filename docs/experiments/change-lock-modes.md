# E3: 되돌릴 사본을 잡는 방식 셋의 비교

이 문서와 `change-lock-modes.csv`는 `ChangeLockModeExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.

- 실행 일시: 2026-09-17T10:44:59.855582+09:00
- PostgreSQL: PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2), 격리 수준 2
- MySQL: MySQL 8.4.11, 격리 수준 4
- 테이블 20000행, 변경 `UPDATE ... SET note = 'exp' WHERE id <= k`, 사본 조건 `amount <= k`(인덱스 있음)
- 동시 작성자 2개(커밋 사이 쉼 0ms 또는 50ms)가 범위 안 임의 행을 쉬지 않고 고쳐 커밋(하나는 `grp`, 하나는 변경과 같은 열 `note`, 둘 다 `ver + 1`). 조합마다 워밍업 2회 버리고 7회
- 락 보유: PESSIMISTIC은 사본 조회 시작부터, OPTIMISTIC·UNCAPTURED는 변경 문장 시작부터 커밋(또는 롤백)까지
- 낡은 행: 재조회한 ver가 사본의 ver와 다른 행. 사본을 뜬 뒤 변경 전에 다른 커밋이 끼어든 행이다
- 버전 열 없이 대조: 바꾸지 않은 열(grp, amount)만 사본과 비교한다. note만 바뀐 행은 변경이 덮어써 비교로 드러나지 않는다

| 기종 | 작성자 쉼(ms) | k | 방식 | 락 보유 중앙값(ms) | 작성자 p95(ms) | 작성자 최대(ms) | 낡은 행이 생긴 실행 | 커밋된 실행 | 버전 열 없이 대조했다면 낡은 사본으로 커밋 |
|---|---|---|---|---|---|---|---|---|---|
| MySQL | 0 | 1000 | PESSIMISTIC | 14.1 | 2.5 | 34.5 | 0/7 | 7/7 | - |
| MySQL | 0 | 1000 | OPTIMISTIC | 13.7 | 1.9 | 13.5 | 7/7 | 0/7 | 0/7 |
| MySQL | 0 | 1000 | UNCAPTURED | 6.4 | 1.7 | 9.6 | 0/7 | 7/7 | - |
| MySQL | 0 | 10000 | PESSIMISTIC | 75.2 | 1.8 | 79.5 | 0/7 | 7/7 | - |
| MySQL | 0 | 10000 | OPTIMISTIC | 97.8 | 1.8 | 100.3 | 7/7 | 0/7 | 0/7 |
| MySQL | 0 | 10000 | UNCAPTURED | 35.1 | 2.1 | 40.7 | 0/7 | 7/7 | - |
| MySQL | 50 | 1000 | PESSIMISTIC | 26.5 | 7.9 | 9.5 | 0/7 | 7/7 | - |
| MySQL | 50 | 1000 | OPTIMISTIC | 21.1 | 6.9 | 11.2 | 0/7 | 7/7 | 0/7 |
| MySQL | 50 | 1000 | UNCAPTURED | 14.3 | 8.0 | 14.7 | 0/7 | 7/7 | - |
| MySQL | 50 | 10000 | PESSIMISTIC | 94.9 | 86.6 | 100.1 | 0/7 | 7/7 | - |
| MySQL | 50 | 10000 | OPTIMISTIC | 115.1 | 56.4 | 75.9 | 6/7 | 1/7 | 0/7 |
| MySQL | 50 | 10000 | UNCAPTURED | 42.8 | 15.6 | 24.5 | 0/7 | 7/7 | - |
| PostgreSQL | 0 | 1000 | PESSIMISTIC | 10.2 | 1.0 | 13.4 | 0/7 | 7/7 | - |
| PostgreSQL | 0 | 1000 | OPTIMISTIC | 5.1 | 1.0 | 6.1 | 7/7 | 0/7 | 0/7 |
| PostgreSQL | 0 | 1000 | UNCAPTURED | 3.3 | 1.0 | 3.2 | 0/7 | 7/7 | - |
| PostgreSQL | 0 | 10000 | PESSIMISTIC | 67.1 | 1.1 | 69.8 | 0/7 | 7/7 | - |
| PostgreSQL | 0 | 10000 | OPTIMISTIC | 49.0 | 1.1 | 49.1 | 7/7 | 0/7 | 0/7 |
| PostgreSQL | 0 | 10000 | UNCAPTURED | 39.4 | 1.0 | 39.7 | 0/7 | 7/7 | - |
| PostgreSQL | 50 | 1000 | PESSIMISTIC | 18.1 | 5.9 | 11.5 | 0/7 | 7/7 | - |
| PostgreSQL | 50 | 1000 | OPTIMISTIC | 13.4 | 4.6 | 10.0 | 0/7 | 7/7 | 0/7 |
| PostgreSQL | 50 | 1000 | UNCAPTURED | 8.2 | 7.2 | 24.2 | 0/7 | 7/7 | - |
| PostgreSQL | 50 | 10000 | PESSIMISTIC | 81.8 | 69.4 | 84.8 | 0/7 | 7/7 | - |
| PostgreSQL | 50 | 10000 | OPTIMISTIC | 46.3 | 10.4 | 40.7 | 6/7 | 1/7 | 2/7 |
| PostgreSQL | 50 | 10000 | UNCAPTURED | 32.0 | 9.3 | 16.6 | 0/7 | 7/7 | - |

- 낡은 행 수(실행별)는 CSV의 `stale_rows`에 있다
