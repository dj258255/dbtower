package io.dbtower.operator;

/**
 * 복제 슬롯 한 개의 상태 (PostgreSQL 전용, C-1) — "슬롯이 붙잡고 있는 WAL이 디스크를 채우는" 사고를 본다.
 *
 * 비활성 슬롯(구독자가 끊긴)은 restart_lsn 이후 WAL을 무한 보존해 디스크를 고갈시키는 PG 운영 최빈
 * 장애다. pg_stat_replication은 "연결된 복제"만 보여주므로 이 사각을 못 본다. 읽기 전용.
 *
 * @param slotName      슬롯 이름
 * @param active        현재 소비자가 붙어 있는가(false면 WAL만 쌓임 — 위험 신호)
 * @param walStatus     wal_status: normal/extended/unreserved/lost. lost면 이미 슬롯 무효(구독자 재구축 필요)
 * @param retainedBytes 이 슬롯 때문에 보존 중인 WAL 바이트(현재 WAL LSN - restart_lsn)
 * @param safeWalSize   max_slot_wal_keep_size까지 남은 여유 바이트. null이면 무제한 보존 설정(그 자체가 주의)
 */
public record ReplicationSlot(String slotName, boolean active, String walStatus,
                              long retainedBytes, Long safeWalSize) {
}
