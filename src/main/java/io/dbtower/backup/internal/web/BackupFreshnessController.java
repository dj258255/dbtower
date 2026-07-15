package io.dbtower.backup.internal.web;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessReport;
import io.dbtower.backup.BackupFreshnessService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백업 신선도 REST (Phase D7).
 *
 * - GET /api/backup-freshness            — 전 인스턴스 요약(나쁜 순 정렬, 카드가 쓴다)
 * - GET /api/instances/{id}/backup-freshness — 인스턴스 하나
 *
 * 읽기 전용 진단이라 인증 사용자면 충분하다(SecurityConfig의 anyRequest().authenticated()에 걸린다).
 * 항상 지금 이력으로 새로 집계한다(캐시 아님).
 */
@RestController
public class BackupFreshnessController {

    private final BackupFreshnessService freshnessService;

    public BackupFreshnessController(BackupFreshnessService freshnessService) {
        this.freshnessService = freshnessService;
    }

    @GetMapping("/api/backup-freshness")
    public BackupFreshnessReport all() {
        return freshnessService.reportAll();
    }

    @GetMapping("/api/instances/{id}/backup-freshness")
    public BackupFreshness one(@PathVariable Long id) {
        return freshnessService.freshnessFor(id);
    }
}
