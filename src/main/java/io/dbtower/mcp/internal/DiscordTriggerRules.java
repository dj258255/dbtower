package io.dbtower.mcp.internal;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord Gateway 봇의 순수 판정 로직 (레퍼런스 "알럿 이모지 → 분석 댓글" 방식) — 연결부와 분리해
 * 단위 테스트가 가능하게 한다. "이 이벤트에 반응해야 하나"와 "어느 인스턴스에 대한 알림인가"만 결정하고,
 * 실제 진단·발송은 봇이 한다.
 */
public final class DiscordTriggerRules {

    /** 알림 embed 제목 "DBTower 회귀 감지 — local-mysql"에서 인스턴스명(마지막 " — " 뒤)을 뽑는다. */
    private static final Pattern TITLE_INSTANCE = Pattern.compile(".*\\s—\\s(\\S.*?)\\s*$");

    private DiscordTriggerRules() {
    }

    /**
     * 이 반응에 진단으로 답해야 하는가 — 트리거 이모지 일치 + 채널·유저 화이트리스트(기본 거부) +
     * 봇 자신의 반응이 아님. SQL·진단이 채팅방에 노출되는 채널이라 노출면을 명시적으로 좁힌다
     * (인바운드 슬래시 커맨드와 같은 원칙).
     */
    public static boolean shouldReact(String emoji, String triggerEmoji, String channelId, String userId,
                                      String botUserId, Set<String> channelAllowlist, Set<String> userAllowlist) {
        if (emoji == null || !emoji.equals(triggerEmoji)) {
            return false;
        }
        if (userId != null && userId.equals(botUserId)) {
            return false; // 봇 자신의 반응 무시(루프 방지)
        }
        return allowed(channelAllowlist, channelId) && allowed(userAllowlist, userId);
    }

    /** 화이트리스트 판정 — 빈 목록 = 전부 거부(기본 거부). 명시된 ID만 통과. */
    public static boolean allowed(Set<String> allowlist, String id) {
        return id != null && !allowlist.isEmpty() && allowlist.contains(id);
    }

    /**
     * 알림 메시지에서 대상 인스턴스명을 추출한다. 우리 알림은 embed 제목이 "DBTower ... — {이름}" 꼴이라
     * 거기서 뽑고, 없으면(멘션 등 자유 텍스트) 텍스트의 "instance={이름}" 패턴을 시도한다. 못 찾으면 null.
     */
    public static String extractInstanceName(String embedTitle, String content) {
        if (embedTitle != null) {
            Matcher m = TITLE_INSTANCE.matcher(embedTitle);
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        if (content != null) {
            Matcher m = Pattern.compile("instance=(\\S+)").matcher(content);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }
}
