package io.dbtower.registry.internal.domain;

import io.dbtower.registry.CredentialPurpose;
import io.dbtower.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 인스턴스의 콘솔 계정 하나(용도별 최대 1개). 스키마의 단일 권위는 V33 마이그레이션이다.
 * 비밀번호는 인스턴스 모니터 계정과 같은 AES-256-GCM 컨버터로 저장한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstanceCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CredentialPurpose purpose;

    @Column(nullable = false)
    private String username;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 512)
    private String password;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public InstanceCredential(Long instanceId, CredentialPurpose purpose, String username, String password) {
        this.instanceId = instanceId;
        this.purpose = purpose;
        this.username = username;
        this.password = password;
        this.updatedAt = LocalDateTime.now();
    }

    public void replace(String username, String password) {
        this.username = username;
        this.password = password;
        this.updatedAt = LocalDateTime.now();
    }
}
