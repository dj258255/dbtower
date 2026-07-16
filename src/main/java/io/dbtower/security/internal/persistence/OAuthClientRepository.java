package io.dbtower.security.internal.persistence;

import io.dbtower.security.internal.domain.OAuthClient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthClientRepository extends JpaRepository<OAuthClient, String> {
}
