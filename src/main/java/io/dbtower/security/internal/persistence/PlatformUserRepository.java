package io.dbtower.security.internal.persistence;

import io.dbtower.security.internal.domain.PlatformUser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {
    Optional<PlatformUser> findByUsername(String username);
}
