package io.dbtower.aiops.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 결과를 돌려보낼 Slack 채널을 팀 라벨로 고른다.
 *
 * <p>팀마다 보는 채널이 다르다 — 한 채널로 몰면 한 팀의 대상 결과가 다른 팀 채널에 섞인다. 채널 하나만
 * 두던 때는 그 섞임을 피하려면 어느 팀에도 채널을 주지 못했다.</p>
 *
 * <p>팀이 정해졌는데 표에 없으면 채널을 주지 않는다(null). 공통 기본 채널로 흘리면 표를 만든 이유가
 * 무너진다 — 그 결과는 DBTower와 n8n에만 남는다. 기본 채널은 팀이 없는(전역) 결과의 자리다.</p>
 *
 * <p>설정 문자열은 게이트웨이의 SLACK_CHANNEL_TEAMS(채널 -> 팀, 들어오는 방향)와 반대다. 여기는 나가는
 * 방향이라 팀 -> 채널로 적는다.</p>
 */
@Component
public class AiOperationChannels {

    private static final Logger log = LoggerFactory.getLogger(AiOperationChannels.class);

    private final Map<String, String> byTeam = new HashMap<>();
    private final Set<String> warnedMissingTeams = ConcurrentHashMap.newKeySet();
    private final String defaultChannel;

    public AiOperationChannels(@Value("${dbtower.aiops.channels.default:}") String defaultChannel,
                               @Value("${dbtower.aiops.channels.by-team:}") String byTeam) {
        this.defaultChannel = blankToNull(defaultChannel);
        for (String entry : byTeam.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split("=", 2);
            String team = parts.length == 2 ? blankToNull(parts[0]) : null;
            String channel = parts.length == 2 ? blankToNull(parts[1]) : null;
            if (team == null || channel == null) {
                // 오타를 조용히 기본 채널로 흘리면 "왜 그 팀 채널로 안 오지"를 못 찾는다 — 기동 로그 한 줄로 남긴다
                log.warn("dbtower.aiops.channels.by-team 조각을 무시합니다(team=채널 형식이어야 함): {}", entry.trim());
                continue;
            }
            this.byTeam.put(team, channel);
        }
    }

    /**
     * 팀 라벨에 맞는 채널. 팀이 있는데 표에 없으면 null(회신 없음) — 기본 채널로 흘리지 않는다.
     * 팀이 없으면(전역) 기본 채널이고, 기본도 비어 있으면 null.
     */
    public String forTeam(String team) {
        String label = blankToNull(team);
        if (label == null) {
            return defaultChannel;
        }
        String channel = byTeam.get(label);
        if (channel == null) {
            // 매번 찍으면 주마다 로그가 쌓인다 — 팀마다 처음 한 번만 남긴다
            if (warnedMissingTeams.add(label)) {
                log.warn("팀 {}의 회신 채널이 표에 없어 Slack으로 보내지 않는다", label);
            }
            return null;
        }
        return channel;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
