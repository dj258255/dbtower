package io.dbtower.mcp.internal;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gateway 봇 순수 판정 (레퍼런스 이모지 트리거) — "반응해야 하나"와 "어느 인스턴스인가".
 * 화이트리스트 기본 거부·봇 자기 반응 무시·embed 제목 파싱이 핵심. Gateway 연결은 라이브 검증.
 */
class DiscordTriggerRulesTest {

    private static final Set<String> CH = Set.of("chan-1");
    private static final Set<String> USR = Set.of("user-1");

    @Test
    void 트리거_이모지_화이트리스트_모두_충족해야_반응한다() {
        assertTrue(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "chan-1", "user-1", "bot-9", CH, USR));
        // 다른 이모지
        assertFalse(DiscordTriggerRules.shouldReact("👍", Set.of("🔍", "🔎"), "chan-1", "user-1", "bot-9", CH, USR));
        // 화이트리스트 밖 채널·유저
        assertFalse(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "other", "user-1", "bot-9", CH, USR));
        assertFalse(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "chan-1", "other", "bot-9", CH, USR));
    }

    @Test
    void 봇_자신의_반응은_무시한다_루프_방지() {
        assertFalse(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "chan-1", "bot-9", "bot-9", CH, USR));
    }

    @Test
    void 화이트리스트는_기본_거부다() {
        assertFalse(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "chan-1", "user-1", "bot-9", Set.of(), USR));
        assertFalse(DiscordTriggerRules.shouldReact("🔍", Set.of("🔍", "🔎"), "chan-1", "user-1", "bot-9", CH, Set.of()));
    }

    @Test
    void embed_제목에서_인스턴스명을_뽑는다() {
        assertEquals("local-mysql",
                DiscordTriggerRules.extractInstanceName("DBTower 회귀 감지 — local-mysql", null));
        assertEquals("dbtower-self",
                DiscordTriggerRules.extractInstanceName("DBTower 운영 경보 — dbtower-self", null));
    }

    @Test
    void 제목이_없으면_텍스트의_instance_패턴을_시도한다() {
        assertEquals("local-postgres",
                DiscordTriggerRules.extractInstanceName(null, "[DBTower 운영 경보] instance=local-postgres\n- ..."));
        // 어디서도 못 찾으면 null
        assertNull(DiscordTriggerRules.extractInstanceName(null, "그냥 잡담"));
        assertNull(DiscordTriggerRules.extractInstanceName(null, null));
    }
}
