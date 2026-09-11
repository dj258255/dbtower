package io.dbtower.workbench.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 인스턴스별 워크벤치 설정. 행이 없으면 기본값(결과 값을 AI에 보내지 않음)이다. */
@Entity
@Table(name = "workbench_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkbenchSetting {

    @Id
    private Long instanceId;

    @Column(nullable = false)
    private boolean allowAiResultValues;

    private String updatedBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public WorkbenchSetting(Long instanceId, boolean allowAiResultValues, String updatedBy) {
        this.instanceId = instanceId;
        update(allowAiResultValues, updatedBy);
    }

    public void update(boolean allowAiResultValues, String updatedBy) {
        this.allowAiResultValues = allowAiResultValues;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }
}
