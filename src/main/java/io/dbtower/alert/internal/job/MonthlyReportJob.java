package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.MonthlyReportService;
import io.dbtower.alert.internal.MonthlyReportService.MonthlyReport;
import io.dbtower.alert.internal.WebhookNotifier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 월간 정기 점검 리포트 발행 잡 (운영 병목 아크 B5, M1). 매월 1일 새벽, 전 인스턴스의 지난 30일
 * 리포트를 만들어 롤업 요약 카드 하나를 웹훅으로 보낸다(인스턴스마다 카드를 쏘면 폭주라 한 장으로).
 * 전문은 콘솔에서 인스턴스별로 조회한다. ShedLock으로 다중 노드에서 한 번만.
 */
@Component
public class MonthlyReportJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportJob.class);

    private final MonthlyReportService reportService;
    private final WebhookNotifier notifier;
    private final String baseUrl;

    public MonthlyReportJob(MonthlyReportService reportService, WebhookNotifier notifier,
                            @Value("${dbtower.base-url:}") String baseUrl) {
        this.reportService = reportService;
        this.notifier = notifier;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /** 매월 1일 09:00(KST 기준 서버 시간). cron은 초 분 시 일 월 요일. */
    @Scheduled(cron = "${dbtower.monthly-report.cron:0 0 9 1 * *}")
    @SchedulerLock(name = "monthly-report", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void publish() {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(30);
        List<MonthlyReport> reports = reportService.generateAll(from, to);
        if (reports.isEmpty() || !notifier.isConfigured()) {
            return;
        }
        String deeplink = baseUrl.isBlank() ? null : baseUrl + "/?view=monthly";
        notifier.sendEmbed(fallback(reports, from, to), null,
                AlertEmbeds.forMonthlyDigest(rollup(reports), from.toLocalDate().toString(),
                        to.toLocalDate().toString(), deeplink));
        log.info("월간 점검 리포트 발행 instances={}", reports.size());
    }

    /** 인스턴스별 한 줄 롤업 — "name: N점(등급)". 나쁜 순(점수 오름차순). */
    private static List<String> rollup(List<MonthlyReport> reports) {
        List<String> lines = new ArrayList<>();
        reports.stream()
                .sorted((a, b) -> Integer.compare(a.score(), b.score()))
                .forEach(r -> lines.add(r.instanceName() + ": " + r.score() + "점 (" + r.grade() + ")"));
        return lines;
    }

    private static String fallback(List<MonthlyReport> reports, LocalDateTime from, LocalDateTime to) {
        StringBuilder sb = new StringBuilder("[DBTower 월간 점검 리포트] ")
                .append(from.toLocalDate()).append(" ~ ").append(to.toLocalDate()).append("\n");
        rollup(reports).forEach(l -> sb.append("- ").append(l).append("\n"));
        return sb.toString();
    }
}
