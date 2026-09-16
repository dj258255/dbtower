package io.dbtower.aiops.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiOperationChannelsTest {

    @Test
    void 팀_표에_있으면_그_팀_채널로_보낸다() {
        AiOperationChannels channels = new AiOperationChannels("C-DEFAULT", "team-a=C111,team-b=C222");

        assertThat(channels.forTeam("team-a")).isEqualTo("C111");
        assertThat(channels.forTeam("team-b")).isEqualTo("C222");
    }

    @Test
    void 표에_없는_팀은_기본_채널이_설정돼_있어도_보내지_않는다() {
        AiOperationChannels channels = new AiOperationChannels("C-DEFAULT", "team-a=C111");

        assertThat(channels.forTeam("team-z")).isNull();
    }

    @Test
    void 팀이_없으면_기본_채널로_보낸다() {
        AiOperationChannels channels = new AiOperationChannels("C-DEFAULT", "team-a=C111");

        assertThat(channels.forTeam(null)).isEqualTo("C-DEFAULT");
        assertThat(channels.forTeam("  ")).isEqualTo("C-DEFAULT");
    }

    @Test
    void 기본_채널이_비면_회신할_곳이_없다() {
        AiOperationChannels channels = new AiOperationChannels("", "team-a=C111");

        assertThat(channels.forTeam(null)).isNull();
        assertThat(channels.forTeam("team-z")).isNull();
        assertThat(channels.forTeam("team-a")).isEqualTo("C111");
    }

    @Test
    void 형식이_어긋난_조각은_버리고_나머지_표는_살려둔다() {
        AiOperationChannels channels = new AiOperationChannels("C-DEFAULT", "team-a,=C1,team-b=C222");

        assertThat(channels.forTeam("team-a")).isNull();
        assertThat(channels.forTeam("team-b")).isEqualTo("C222");
    }
}
