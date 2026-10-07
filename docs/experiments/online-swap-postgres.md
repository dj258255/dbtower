# E7b: 온라인 변경 — 배치 커밋 대 트리거 교체 (PostgreSQL)

이 문서와 `online-swap-postgres.csv`는 `OnlineSwapPostgresExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.

- 실행 일시: 2026-10-08T03:55:46.710806+09:00
- 대상: 전용 PostgreSQL 16(16434, wal_level=logical, tmpfs)
- 변경: `swap_scale` 1,000,000행의 note에 'v2:' 접두. BATCH는 제자리 1,000행 배치(쉼 100ms), SWAP_TRIGGER는 pt-osc 순서(트리거 설치 → 양보 복사 → 한 트랜잭션 RENAME)
- 동시 writer 2개: 임의 행 UPDATE 90% + 새 행 INSERT 10%
- 트리거는 동기 캡처라 drain이 없고 그 비용은 writer 소요에 직접 실린다 — BATCH 대비 writer p95 차이가 트리거 오버헤드다
- slot backlog: 소비자 없는 logical 슬롯(test_decoding)의 WAL 적체 최대 — 캡처 스트림을 켜 두는 것 자체의 비용 관측

| 방식 | 총 소요 | 컷오버 | 혼합 노출 | 유실 | INSERT 누락 | 변환 누락 | writer p50 | p95 | max | 최장 공백 | 커밋 수 | 추가 공간 | 슬롯 적체 최대 | 컷오버 유출 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BATCH | 123.2초 | 0ms | 123.1초 | 0 | 0 | 55971 | 1.30ms | 6.04ms | 275ms | 237ms | 97616 | 0MB | 243.7MB | 0 |
| SWAP_TRIGGER | 6.6초 | 21ms | 0.9초 | 0 | 0 | 6256 | 1.65ms | 4.43ms | 35ms | 34ms | 6292 | 64MB | 243.7MB | 3 |
