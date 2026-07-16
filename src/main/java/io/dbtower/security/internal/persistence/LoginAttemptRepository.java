package io.dbtower.security.internal.persistence;

import io.dbtower.security.internal.domain.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, String> {
}
