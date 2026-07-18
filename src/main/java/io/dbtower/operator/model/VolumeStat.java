package io.dbtower.operator.model;

/**
 * 인스턴스 수준 저장 용량 한도 — 용량 예측의 임계 원천 ②("기종이 스스로 아는 것").
 *
 * 기종별 의미(정직 표기 — 모르는 값은 null, 지어내지 않는다):
 * - SQL Server: dm_os_volume_stats — DB 파일이 사는 볼륨들의 총량/여유(물리 디스크 관점).
 *   maxBytes는 null(볼륨이 한도).
 * - Oracle: dba_data_files — totalBytes=현재 할당 합, maxBytes=autoextend 상한 합
 *   (autoextend 아닌 파일은 현재 크기가 상한). availableBytes는 null(파일 관점이라 볼륨 여유를 모름).
 * - MySQL/PostgreSQL/MongoDB: SQL로 볼륨을 볼 수 없다 — 조회 자체가 없다(UNSUPPORTED).
 */
public record VolumeStat(Long totalBytes, Long availableBytes, Long maxBytes) {
}
