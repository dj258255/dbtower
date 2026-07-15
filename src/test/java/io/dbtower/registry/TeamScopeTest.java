package io.dbtower.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 팀 스코프(LBAC, Phase 3) — 단일 경계(RegistryService.findAll/findById)의 네 규칙과
 * "스코프 밖 = 미등록과 같은 404 메시지"(존재 노출 방지)를 검증한다.
 */
class TeamScopeTest {

    private final DatabaseInstanceRepository repository = Mockito.mock(DatabaseInstanceRepository.class);
    private final InstanceOperations operations = Mockito.mock(InstanceOperations.class);
    private final RegistryService service = new RegistryService(repository, operations,
            Mockito.mock(ApplicationEventPublisher.class));

    private final DatabaseInstance teamA = withTeam("a-db", "team-a");
    private final DatabaseInstance teamB = withTeam("b-db", "team-b");
    private final DatabaseInstance global = withTeam("global-db", null);

    private static DatabaseInstance withTeam(String name, String team) {
        DatabaseInstance i = new DatabaseInstance(name, DbmsType.MYSQL, "h", 3306, "db", "u", "p");
        i.updateMeta(team, null);
        return i;
    }

    private void loginAs(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증_없는_폴러는_전역을_본다() {
        when(repository.findAll()).thenReturn(List.of(teamA, teamB, global));
        assertThat(service.findAll()).hasSize(3);
    }

    @Test
    void ADMIN은_팀_라벨이_있어도_전역이다() {
        loginAs("ROLE_ADMIN", "TEAM_team-a");
        when(repository.findAll()).thenReturn(List.of(teamA, teamB, global));
        assertThat(service.findAll()).hasSize(3);
    }

    @Test
    void 팀_사용자는_자기_팀과_전역_인스턴스만_본다() {
        loginAs("ROLE_VIEWER", "TEAM_team-a");
        when(repository.findAll()).thenReturn(List.of(teamA, teamB, global));
        assertThat(service.findAll()).extracting(DatabaseInstance::getName)
                .containsExactly("a-db", "global-db");
    }

    @Test
    void 라벨_없는_사용자는_전역이다_하위호환() {
        loginAs("ROLE_VIEWER");
        when(repository.findAll()).thenReturn(List.of(teamA, teamB, global));
        assertThat(service.findAll()).hasSize(3);
    }

    @Test
    void 스코프_밖_단건_조회는_미등록과_같은_404_메시지다_존재_노출_방지() {
        loginAs("ROLE_VIEWER", "TEAM_team-a");
        when(repository.findById(7L)).thenReturn(Optional.of(teamB));
        when(repository.findById(999L)).thenReturn(Optional.empty());

        String outOfScope = catchMessage(7L);
        String notFound = catchMessage(999L);
        // 메시지가 id만 다르고 형식이 같아야 — 스코프 밖과 미등록이 구분 불가
        assertThat(outOfScope).isEqualTo("등록되지 않은 인스턴스: 7");
        assertThat(notFound).isEqualTo("등록되지 않은 인스턴스: 999");
    }

    private String catchMessage(Long id) {
        try {
            service.findById(id);
            throw new AssertionError("예외가 나야 한다");
        } catch (InstanceNotFoundException e) {
            return e.getMessage();
        }
    }

    @Test
    void 팀_사용자도_자기_팀_인스턴스는_단건_조회된다() {
        loginAs("ROLE_VIEWER", "TEAM_team-a");
        when(repository.findById(1L)).thenReturn(Optional.of(teamA));
        assertThat(service.findById(1L).getName()).isEqualTo("a-db");
    }
}
