package io.dbtower.backup;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 전 인스턴스 백업 신선도 요약 (Phase D7) — 웹 콘솔 "백업 신선도" 카드와 REST 응답의 한 단위.
 *
 * DBA 일일 점검("모든 DB가 최근에 백업됐나")을 한 화면에 답한다. instances는 나쁜 순으로 정렬해
 * 오래된 것·백업 없는 것이 위로 오게 한다(NO_BACKUP → STALE → FRESH, 같은 그룹은 오래된 순).
 *
 * @param checkedAt      집계 시각
 * @param thresholdHours 신선 판정 임계(시간)
 * @param total          전체 인스턴스 수
 * @param freshCount     임계 이내로 최신인 인스턴스 수
 * @param staleCount     마지막 백업이 임계를 넘긴 인스턴스 수
 * @param noBackupCount  성공한 백업 이력이 없는 인스턴스 수(사각지대)
 * @param instances      인스턴스별 신선도(나쁜 순 정렬)
 */
public record BackupFreshnessReport(LocalDateTime checkedAt, int thresholdHours,
                                    int total, int freshCount, int staleCount, int noBackupCount,
                                    List<BackupFreshness> instances) {

    /** 나쁜 순 정렬 우선순위 — 사각지대를 맨 위로. */
    private static final Comparator<BackupFreshness> WORST_FIRST = Comparator
            .comparingInt((BackupFreshness f) -> switch (f.status()) {
                case NO_BACKUP -> 0;
                case STALE -> 1;
                case FRESH -> 2;
            })
            // 같은 그룹 안에서는 경과가 큰(오래된) 순 — elapsedHours가 없는 NO_BACKUP은 서로 동률
            .thenComparing(f -> f.elapsedHours() == null ? 0.0 : -f.elapsedHours());

    /** 집계·정렬은 호출부가 아니라 여기서 — 목록만 주면 카운트와 정렬을 파생한다. */
    public static BackupFreshnessReport of(LocalDateTime checkedAt, int thresholdHours,
                                           List<BackupFreshness> instances) {
        int fresh = 0, stale = 0, noBackup = 0;
        for (BackupFreshness f : instances) {
            switch (f.status()) {
                case FRESH -> fresh++;
                case STALE -> stale++;
                case NO_BACKUP -> noBackup++;
            }
        }
        List<BackupFreshness> sorted = instances.stream().sorted(WORST_FIRST).toList();
        return new BackupFreshnessReport(checkedAt, thresholdHours,
                instances.size(), fresh, stale, noBackup, sorted);
    }
}
