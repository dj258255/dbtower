package io.dbtower.registry;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/** 이기종 DB 인스턴스 등록·조회. 등록 시 접속 검증을 함께 수행한다. */
@Service
public class RegistryService {

    private final DatabaseInstanceRepository repository;
    private final InstanceOperations operations;
    private final ApplicationEventPublisher events;

    public RegistryService(DatabaseInstanceRepository repository, InstanceOperations operations,
                           ApplicationEventPublisher events) {
        this.repository = repository;
        this.operations = operations;
        this.events = events;
    }

    /** 등록 전에 실제로 붙어보고, 붙지 않으면 등록을 거부한다 — 죽은 인스턴스가 레지스트리에 쌓이는 것 방지 */
    public DatabaseInstance register(DatabaseInstance instance) {
        HealthStatus health = operations.health(instance);
        if (!health.up()) {
            throw new IllegalArgumentException("접속 실패로 등록 거부: " + health.message());
        }
        return repository.save(instance);
    }

    /**
     * 멱등 등록(upsert) — IaC(Ansible/K8s/Terraform)가 프로비저닝 후 등록을 재실행해도
     * 중복이 아니라 갱신이 되게 한다. 같은 이름이 있으면 접속 정보를 덮고, 없으면 새로 등록.
     * 어느 경로든 등록 전에 실제 접속을 검증한다(register와 동일한 fail-closed).
     */
    public DatabaseInstance upsert(String name, DbmsType type, String host, int port,
                                   String dbName, String username, String password, boolean useTls,
                                   String teamLabel, String consoleUrl, String nodeFilter,
                                   String environment, String region, String clusterLabel) {
        DatabaseInstance existing = repository.findByName(name).orElse(null);
        if (existing == null) {
            DatabaseInstance created = new DatabaseInstance(name, type, host, port, dbName, username, password, useTls);
            created.updateMeta(teamLabel, consoleUrl, nodeFilter, environment, region, clusterLabel);
            return register(created);
        }
        existing.updateConnection(type, host, port, dbName, username, password, useTls);
        existing.updateMeta(teamLabel, consoleUrl, nodeFilter, environment, region, clusterLabel);
        HealthStatus health = operations.health(existing);
        if (!health.up()) {
            throw new IllegalArgumentException("접속 실패로 갱신 거부: " + health.message());
        }
        // 접속 정보가 바뀌었으니 기존 커넥션 풀은 정리 — 다음 조회 때 새 정보로 다시 연다
        operations.release(existing.getId());
        return repository.save(existing);
    }

    /**
     * 팀 스코프(LBAC, Phase 3) 강제 지점 — 모든 모듈이 인스턴스를 이 두 메서드로 얻으므로 여기가
     * <b>단일 경계</b>다(컨트롤러마다 뿌리지 않는다). 스코프 규칙:
     * <ul>
     *   <li>인증 없음(백그라운드 폴러·잡) = 전역 — 수집·경보는 팀과 무관하게 전체를 지켜야 한다</li>
     *   <li>ROLE_ADMIN(서비스 토큰 포함) = 전역 — 관리자가 자기 눈을 가리면 관리가 안 된다</li>
     *   <li>TEAM_라벨 authority 보유 = 그 팀 인스턴스 + 라벨 없는 전역 인스턴스만</li>
     *   <li>라벨 없는 사용자 = 전역(하위 호환)</li>
     * </ul>
     * 스코프 밖 단건 조회는 403이 아니라 <b>404와 동일한 예외</b> — 존재 자체를 노출하지 않는다.
     */
    public List<DatabaseInstance> findAll() {
        String team = currentTeamScope();
        List<DatabaseInstance> all = repository.findAll();
        if (team == null) {
            return all;
        }
        return all.stream().filter(i -> inScope(i, team)).toList();
    }

    public DatabaseInstance findById(Long id) {
        DatabaseInstance instance = repository.findById(id)
                .orElseThrow(() -> new InstanceNotFoundException(id));
        String team = currentTeamScope();
        if (team != null && !inScope(instance, team)) {
            throw new InstanceNotFoundException(id);   // 미등록과 같은 메시지 — 존재 노출 방지
        }
        return instance;
    }

    /** 라벨 없는 인스턴스는 전역(모든 팀이 봄), 라벨이 있으면 같은 팀만. */
    private static boolean inScope(DatabaseInstance instance, String team) {
        return instance.getTeamLabel() == null || instance.getTeamLabel().isBlank()
                || team.equals(instance.getTeamLabel());
    }

    /** 현재 요청 주체의 팀 스코프 — null이면 전역(폴러·ADMIN·라벨 없는 사용자). */
    private static String currentTeamScope() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean admin = false;
        String team = null;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if ("ROLE_ADMIN".equals(name)) {
                admin = true;
            } else if (name.startsWith("TEAM_")) {
                team = name.substring("TEAM_".length());
            }
        }
        return admin ? null : team;
    }

    public HealthStatus health(Long id) {
        return operations.health(findById(id));
    }

    /** 수집 활성/격리 토글 (Phase F) — 문제 인스턴스를 삭제하지 않고 관제에서 잠시 뺀다. */
    public DatabaseInstance setCollectionEnabled(Long id, boolean enabled) {
        DatabaseInstance instance = findById(id);
        instance.setCollectionEnabled(enabled);
        return repository.save(instance);
    }

    public void delete(Long id) {
        // 자식 행(query_snapshot·plan_snapshot·backup_run·backup_policy_entity·health_sample)은
        // FK ON DELETE CASCADE(V10)가 DB에서 함께 지운다 — 여기선 부모 한 행만 지운다.
        repository.deleteById(id);
        // 커넥션 풀·클라이언트 정리(대상 접속 자원).
        operations.release(id);
        // 폴러들이 폴 사이에 들고 있는 인메모리 상태(히스토그램·데드락 카운터/시그·쿨다운·백오프)를
        // 각 모듈이 @EventListener로 비우게 알린다 — DB CASCADE가 닿지 못하는 프로세스 메모리 정리(B-1).
        events.publishEvent(new InstanceDeletedEvent(id));
    }
}
