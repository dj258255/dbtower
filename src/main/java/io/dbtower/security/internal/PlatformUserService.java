package io.dbtower.security.internal;

import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 플랫폼 사용자 조회(UserDetailsService) + 최초 기동 부트스트랩.
 *
 * 부트스트랩 원칙: 사용자가 하나도 없으면 admin 계정을 만든다.
 * 비밀번호는 환경변수(DBTOWER_ADMIN_PASSWORD)가 있으면 그 값, 없으면 랜덤 생성 후
 * 로그로 1회 안내 — 기본 비밀번호를 하드코딩하는 것(admin/admin)이 최악의 선택이라서다.
 */
@Service
public class PlatformUserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformUserService.class);

    private final PlatformUserRepository repository;
    private final PasswordEncoder encoder;

    public PlatformUserService(PlatformUserRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        PlatformUser user = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("없는 사용자: " + username));
        // 팀 스코프(LBAC)는 authority로 실어 나른다 — 강제 지점(RegistryService)이 security 모듈을
        // 참조하지 않고 SecurityContext만 읽으면 되게(모듈 경계 유지). 라벨 변경은 재로그인부터 적용.
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (user.getTeamLabel() != null && !user.getTeamLabel().isBlank()) {
            authorities.add(new SimpleGrantedAuthority("TEAM_" + user.getTeamLabel()));
        }
        return new User(user.getUsername(), user.getPasswordHash(), authorities);
    }

    @Bean
    ApplicationRunner bootstrapUsers(
            @Value("${dbtower.security.admin-password:}") String adminPassword,
            @Value("${dbtower.security.viewer-password:}") String viewerPassword,
            @Value("${dbtower.security.requester-password:}") String requesterPassword,
            @Value("${dbtower.security.approver-password:}") String approverPassword,
            @Value("${dbtower.security.operator-password:}") String operatorPassword) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            String password = adminPassword.isBlank() ? randomPassword() : adminPassword;
            repository.save(new PlatformUser("admin", encoder.encode(password), PlatformUser.Role.ADMIN));
            if (adminPassword.isBlank()) {
                log.warn("admin 계정을 생성했습니다. 초기 비밀번호: {} — 로그인 후 변경하거나 "
                        + "DBTOWER_ADMIN_PASSWORD로 고정하세요", password);
            } else {
                log.info("admin 계정을 생성했습니다 (비밀번호는 DBTOWER_ADMIN_PASSWORD)");
            }
            // 역할별 계정은 비밀번호를 준 것만 만든다 — 쓰지 않는 역할의 계정이 빈 비밀번호나 랜덤값으로 남지 않게
            seedIfSet("viewer", viewerPassword, PlatformUser.Role.VIEWER, "관제 조회 전용");
            seedIfSet("requester", requesterPassword, PlatformUser.Role.REQUESTER, "워크벤치 조회·변경 요청");
            seedIfSet("approver", approverPassword, PlatformUser.Role.APPROVER, "변경 승인·반려");
            seedIfSet("operator", operatorPassword, PlatformUser.Role.OPERATOR, "승인 티켓 실행·대상 DB 운영");
        };
    }

    private void seedIfSet(String username, String password, PlatformUser.Role role, String purpose) {
        if (password.isBlank()) {
            return;
        }
        repository.save(new PlatformUser(username, encoder.encode(password), role));
        log.info("{} 계정을 생성했습니다 ({}, {})", username, role, purpose);
    }

    private String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
