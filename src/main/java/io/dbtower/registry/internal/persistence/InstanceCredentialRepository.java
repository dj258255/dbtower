package io.dbtower.registry.internal.persistence;

import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.internal.domain.InstanceCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstanceCredentialRepository extends JpaRepository<InstanceCredential, Long> {

    Optional<InstanceCredential> findByInstanceIdAndPurpose(Long instanceId, CredentialPurpose purpose);

    List<InstanceCredential> findByInstanceIdOrderByPurposeAsc(Long instanceId);
}
