package io.dbtower.registry.internal.persistence;

import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.CredentialSummary;
import io.dbtower.registry.internal.domain.InstanceCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InstanceCredentialRepository extends JpaRepository<InstanceCredential, Long> {

    Optional<InstanceCredential> findByInstanceIdAndPurpose(Long instanceId, CredentialPurpose purpose);

    /**
     * 요약 — 비밀번호 칸을 읽지 않는다. 엔티티를 불러오면 컨버터가 비밀번호를 복호화하는데, 목록은 "계정이 있나"만 필요하다.
     * 키 없이 뜬 앱에서 암호화된 행 하나가 워크벤치 인스턴스 목록 전체를 실패시켰다.
     */
    @Query("select new io.dbtower.registry.CredentialSummary(c.purpose, c.username, c.updatedAt)"
            + " from InstanceCredential c where c.instanceId = :instanceId order by c.purpose asc")
    List<CredentialSummary> findSummaries(@Param("instanceId") Long instanceId);
}
