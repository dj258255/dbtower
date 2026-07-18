package io.dbtower.alert.internal;

import io.dbtower.advisor.AdvisorCheck;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.advisor.Severity;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.finops.FinOpsQuery;
import io.dbtower.finops.WasteSummary;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.ScoreQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 월간 정기 점검 리포트 조립 (운영 병목 아크 B5). DBA가 매달 손으로 만드는 정기 점검 보고서를
 * 자동화한다 — 인시던트 리포트(B4)가 "장애 구간의 사건"이라면, 이쪽은 "기간 전체의 건강"이다.
 *
 * 재료: 헬스 스코어(ScoreQuery)·백업 신선도와 복원 검증(BackupFreshnessService)·Advisor 스윕과
 * 용량 예측(AdvisorService, disk-forecast 포함)·낭비 신호(FinOpsQuery)·설정 변경 건수(ConfigDriftService).
 * 전부 공개 API 조합 — 신규 수집 0. 대상 DB는 바꾸지 않는다(읽고 종합만).
 */
@Service
public class MonthlyReportService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportService.class);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int TOP_FINDINGS = 5;

    private final RegistryService registryService;
    private final ScoreQuery scoreQuery;
    private final AdvisorService advisorService;
    private final BackupFreshnessService freshnessService;
    private final FinOpsQuery finOpsQuery;
    private final ConfigDriftService configDriftService;

    public MonthlyReportService(RegistryService registryService, ScoreQuery scoreQuery,
                                AdvisorService advisorService, BackupFreshnessService freshnessService,
                                FinOpsQuery finOpsQuery, ConfigDriftService configDriftService) {
        this.registryService = registryService;
        this.scoreQuery = scoreQuery;
        this.advisorService = advisorService;
        this.freshnessService = freshnessService;
        this.finOpsQuery = finOpsQuery;
        this.configDriftService = configDriftService;
    }

    public record MonthlyReport(long instanceId, String instanceName, String from, String to,
                                int score, String grade, String markdown) {
    }

    /** 한 인스턴스의 월간 리포트. from~to는 기간(기본 최근 30일). */
    public MonthlyReport generate(Long instanceId, LocalDateTime from, LocalDateTime to) {
        DatabaseInstance instance = registryService.findById(instanceId); // LBAC 게이트
        HealthScoreView health = scoreQuery.scoreFor(instanceId);
        BackupFreshness backup = freshnessService.freshnessFor(instance);
        InstanceAdvisorReport advisor = advisorService.inspect(instance);
        WasteSummary waste = safeWaste(instanceId);
        int configChanges = configDriftService.changesInWindow(instanceId, from, to).size();

        String md = render(instance, from, to, health, backup, advisor, waste, configChanges);
        return new MonthlyReport(instanceId, instance.getName(), D.format(from), D.format(to),
                health.score(), health.grade(), md);
    }

    /** 전 인스턴스 리포트 — 스케줄 잡이 부른다. 한 인스턴스 실패가 나머지를 막지 않는다. */
    public List<MonthlyReport> generateAll(LocalDateTime from, LocalDateTime to) {
        return registryService.findAll().stream()
                .map(i -> {
                    try {
                        return generate(i.getId(), from, to);
                    } catch (Exception e) {
                        log.warn("월간 리포트 생성 실패 instance={} cause={}", i.getName(), e.getMessage());
                        return null;
                    }
                })
                .filter(r -> r != null)
                .toList();
    }

    private WasteSummary safeWaste(Long instanceId) {
        try {
            return finOpsQuery.wasteSummary(instanceId);
        } catch (RuntimeException e) {
            return new WasteSummary(instanceId, 0, false);
        }
    }

    private String render(DatabaseInstance instance, LocalDateTime from, LocalDateTime to,
                          HealthScoreView health, BackupFreshness backup, InstanceAdvisorReport advisor,
                          WasteSummary waste, int configChanges) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 월간 점검 리포트 — ").append(instance.getName())
                .append(" (").append(instance.getType()).append(")\n\n");
        sb.append("- 기간: ").append(D.format(from)).append(" ~ ").append(D.format(to)).append("\n\n");

        sb.append("## 헬스 스코어\n");
        sb.append(health.score()).append("점 (").append(health.grade()).append(")");
        if (health.down()) {
            sb.append(" · 현재 다운");
        }
        sb.append("\n\n");

        sb.append("## 백업\n");
        sb.append("상태: ").append(backup.status());
        if (backup.lastBackupAt() != null) {
            sb.append(" · 마지막 백업 ").append(TS.format(backup.lastBackupAt()));
        }
        if (backup.verifyStatus() != null) {
            sb.append(" · 복원 검증 ").append(backup.verifyStatus());
        }
        sb.append("\n\n");

        sb.append("## Advisor 점검\n");
        sb.append("CRITICAL ").append(advisor.critical())
                .append(" · WARNING ").append(advisor.warning())
                .append(" · INFO ").append(advisor.info()).append("\n");
        // 용량 예측(disk-forecast)은 별도로 부각 — 월간 리포트의 핵심 선행 지표
        advisor.checks().stream()
                .filter(c -> "disk-forecast".equals(c.advisor()))
                .flatMap(c -> c.findings().stream())
                .findFirst()
                .ifPresent(f -> sb.append("- 용량 예측: ").append(f.title())
                        .append(" — ").append(f.detail()).append("\n"));
        appendTopFindings(sb, advisor);
        sb.append("\n");

        sb.append("## 낭비 신호 (FinOps)\n");
        sb.append(waste.supported() ? "낭비 후보 " + waste.candidateCount() + "건" : "미지원 기종(판정 불가)").append("\n\n");

        sb.append("## 설정 변경\n");
        sb.append("기간 내 ").append(configChanges).append("건\n\n");

        sb.append("## 원칙\n");
        sb.append("- 전부 읽고 종합한 신호·조언입니다. 대상 DB는 바꾸지 않으며 조치·실행은 사람이 합니다.\n");
        return sb.toString();
    }

    private void appendTopFindings(StringBuilder sb, InstanceAdvisorReport advisor) {
        List<String> lines = advisor.checks().stream()
                .flatMap(c -> c.findings().stream())
                .filter(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.WARNING)
                .limit(TOP_FINDINGS)
                .map(f -> "- [" + f.severity() + "] " + f.title())
                .toList();
        lines.forEach(l -> sb.append(l).append("\n"));
    }
}
