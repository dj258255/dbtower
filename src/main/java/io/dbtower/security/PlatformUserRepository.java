package io.dbtower.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {
    Optional<PlatformUser> findByUsername(String username);
}
