# E5: 복제 슬롯의 WAL 보존 상한: 디스크 대 이어받기

이 문서와 `wal-slot-limit.csv`는 `wal_slot_limit.py`가 생성했다. 숫자를 손으로 고치지 않는다.

- 실행 일시: 2026-09-17T12:42:33.184558+09:00
- 일회용 컨테이너 postgres:16 (16.15 (Debian 16.15-1.pgdg13+2)), max_wal_size=32MB, checkpoint_timeout=30s, wal_keep_size=0, 데이터는 tmpfs
- 수신기(pg_receivewal --slot)를 죽인 뒤 라운드마다 150,000행 삽입 + WAL 전환 + CHECKPOINT, 8라운드
- pg_wal 크기는 pg_ls_waldir() 합계

## 수신기가 죽어 있는 동안

| 라운드 | 무제한: pg_wal(MB) | 무제한: 슬롯 상태 | 상한 32MB: pg_wal(MB) | 상한 32MB: 슬롯 상태 | 상한 32MB: 남은 안전 크기(MB) |
|---|---|---|---|---|---|
| 1 | 64.0 | reserved | 32.0 | lost | null |
| 2 | 96.0 | extended | 32.0 | lost | null |
| 3 | 128.0 | extended | 32.0 | lost | null |
| 4 | 160.0 | extended | 32.0 | lost | null |
| 5 | 192.0 | extended | 32.0 | lost | null |
| 6 | 224.0 | extended | 32.0 | lost | null |
| 7 | 256.0 | extended | 32.0 | lost | null |
| 8 | 288.0 | extended | 32.0 | lost | null |

## 수신기를 다시 붙였을 때

| 설정 | 최종 슬롯 상태 | 재시작한 수신기가 살아 있나 | 받은 세그먼트 수 | 세그먼트 구멍 | 수신기 로그 끝 |
|---|---|---|---|---|---|
| unlimited (-1) | extended | True | 18 | 0건 | (없음) |
| limit32 (32MB) | lost | True | 1 | 0건 | 0000002 has already been removed pg_receivewal: disconnected; waiting 5 seconds to try again pg_receivewal: error: unexpected termination of replication stream: ERROR:  requested WAL segment 000000010000000000000002 has already been removed pg_receivewal: disconnected; waiting 5 seconds to try again |

- 읽는 법: `재시작한 수신기가 살아 있나`는 프로세스 생존만 본다. 슬롯이 lost면 프로세스는 살아서 재시도만 반복한다(로그 끝 참고)
- `세그먼트 구멍 0건`은 받은 세그먼트가 2개 이상일 때만 연속을 뜻한다. 1개면 비교할 쌍이 없어 0이 나온다
