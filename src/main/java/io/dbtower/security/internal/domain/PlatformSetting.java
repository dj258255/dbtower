package io.dbtower.security.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 플랫폼 자체 설정 키-값 (Phase 1) — 재시작에도 살아남아야 하는 소량의 운영 값(첫 용도: API 토큰).
 * 대상 DB가 아니라 플랫폼 메타 DB에만 산다.
 */
@Entity
@Table(name = "platform_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 512)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PlatformSetting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = LocalDateTime.now();
    }

    public void update(String value) {
        this.value = value;
        this.updatedAt = LocalDateTime.now();
    }
}
