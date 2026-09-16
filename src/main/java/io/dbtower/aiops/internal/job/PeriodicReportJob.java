package io.dbtower.aiops.internal.job;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.aiops.internal.AiOperationChannels;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

/**
 * 주 1회 팀별 운영 요약을 접수한다. 접수만 하고 실행은 실행기(integrations/ai-ops-gateway)가 한다.
 *
 * <p>기본은 꺼짐이다. 켜면 주마다 팀 수만큼 모델 호출이 생긴다 — 경보와 달리 아무도 요청하지 않아도
 * 도는 호출이라 조직이 원할 때 명시적으로 켠다.</p>
 *
 * <p>작업 하나가 한 팀을 덮는다(범위 전체 = instanceId null, 사실 수집은 그 팀 라벨로 걸린다). 팀을 하나로
 * 합치면 리포트가 섞이고, 인스턴스마다 하나씩 만들면 같은 팀의 인스턴스 수만큼 모델 호출이 늘어난다.</p>
 *
 * <p>requestId를 주 단위로 만든다 — 같은 주에 노드가 겹쳐 돌거나 사람이 손으로 돌려도 작업은 하나다.</p>
 */
@Component
public class PeriodicReportJob {

    private static final Logger log = LoggerFactory.getLogger(PeriodicReportJob.class);

    /** 팀 없는 인스턴스(null 키)가 먼저 오도록 정렬한다 — 순서를 고정해야 같은 주에 같은 순서로 돈다 */
    private static final Comparator<String> NULL_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final int WINDOW_MINUTES = 7 * 24 * 60;
    private static final String REQUESTER = "schedule:weekly-report";

    private final AiOperationService service;
    private final RegistryService registry;
    private final AiOperationChannels channels;
    private final boolean enabled;
    private final Clock clock;

    // 시계 주입 생성자는 테스트가 시각을 고정하는 시임이다 — AiOperationService와 같은 방식(공개는 시스템 시계)
    @Autowired
    public PeriodicReportJob(AiOperationService service, RegistryService registry, AiOperationChannels channels,
                             @Value("${dbtower.aiops.periodic-report.enabled:false}") boolean enabled) {
        this(service, registry, channels, enabled, Clock.systemDefaultZone());
    }

    PeriodicReportJob(AiOperationService service, RegistryService registry, AiOperationChannels channels,
                      boolean enabled, Clock clock) {
        this.service = service;
        this.registry = registry;
        this.channels = channels;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Scheduled(cron = "${dbtower.aiops.periodic-report.cron:0 0 9 * * MON}")
    @SchedulerLock(name = "aiops-periodic-report", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scheduledRun() {
        run();
    }

    /** 락 없는 본체 — 스케줄 진입점만 분산 락을 쥔다(리퍼와 같은 규율, 테스트가 직접 부른다) */
    public void run() {
        if (!enabled) {
            return;
        }
        String weekStart = LocalDate.now(clock).with(DayOfWeek.MONDAY).toString();
        int submitted = 0;
        for (String team : teams()) {
            try {
                service.submit(new AiOperationRequest("periodic:" + teamKey(team) + ":" + weekStart,
                        AiOperationType.PERIODIC_REPORT, null, WINDOW_MINUTES,
                        "지난 7일 운영 상태를 요약해줘. 팀: " + (team == null ? "전체" : team),
                        AiOperationTrigger.SCHEDULE, REQUESTER + (team == null ? "" : ":" + team), team,
                        channels.forTeam(team), null));
                submitted++;
            } catch (RuntimeException e) {
                // 한 팀의 상한 초과가 나머지 팀의 리포트를 막으면 안 된다 — 그 팀만 건너뛴다
                log.warn("정기 리포트 접수 실패 team={}: {}", team == null ? "(팀 없음)" : team, e.getMessage());
            }
        }
        log.info("정기 리포트 접수: {}팀", submitted);
    }

    /** 팀 라벨이 길면 requestId 상한(120자)에 걸려 그 팀만 매주 조용히 빠진다 — 긴 라벨은 해시로 줄인다 */
    private static String teamKey(String team) {
        if (team == null) {
            return "-";
        }
        return team.length() <= 40 ? team
                : Integer.toHexString(team.hashCode()) + ":" + team.substring(0, 24);
    }

    /**
     * 리포트를 받을 팀 목록 — 라벨 없는 인스턴스는 팀 없음 한 묶음(null 키)으로 친다. 빠뜨리면 그 대상은
     * 어떤 리포트에도 안 나오고, 모든 팀에 넣으면 중복이 된다. 순서를 고정해 같은 주에 같은 순서로 돈다.
     */
    private Set<String> teams() {
        Set<String> teams = new TreeSet<>(NULL_FIRST);
        for (DatabaseInstance instance : registry.findAll()) {
            String label = instance.getTeamLabel();
            teams.add(label == null || label.isBlank() ? null : label.trim());
        }
        return teams;
    }
}
