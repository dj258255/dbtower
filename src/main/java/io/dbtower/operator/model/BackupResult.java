package io.dbtower.operator.model;

/** 백업 실행 결과 — 산출물 위치와 크기 */
public record BackupResult(String location, long bytes) {
}
