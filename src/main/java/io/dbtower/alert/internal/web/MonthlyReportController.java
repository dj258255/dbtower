package io.dbtower.alert.internal.web;

import io.dbtower.alert.internal.MonthlyReportService;
import io.dbtower.alert.internal.MonthlyReportService.MonthlyReport;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 월간 점검 리포트 API (운영 병목 아크 B5, M3). 콘솔에서 인스턴스별 리포트를 즉시 생성한다
 * (데모·수동 트리거). 헬스·백업·설정 값을 담아 파라미터 조회와 같은 ADMIN 경계(SecurityConfig).
 * 정기 발행은 MonthlyReportJob(@monthly)이 별도로 한다.
 */
@RestController
public class MonthlyReportController {

    private final MonthlyReportService reportService;

    public MonthlyReportController(MonthlyReportService reportService) {
        this.reportService = reportService;
    }

    /** 수동 생성 — days 기본 30일. 콘솔이 마크다운을 받아 렌더·다운로드. */
    @PostMapping("/api/instances/{id}/monthly-report")
    public MonthlyReport generate(@PathVariable Long id,
                                  @RequestParam(defaultValue = "30") int days) {
        LocalDateTime to = LocalDateTime.now();
        return reportService.generate(id, to.minusDays(days), to);
    }
}
