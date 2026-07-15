package io.dbtower.security.internal;

import io.dbtower.security.internal.domain.PlatformSetting;
import io.dbtower.security.internal.persistence.PlatformSettingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 플랫폼 자체 설정의 영속 저장소 (Phase 1). "있으면 읽고, 없으면 만들어 저장하고 돌려준다".
 * 첫 용도는 API 토큰을 재시작에 살아남게 하는 것 — 매 기동 랜덤 재생성으로 MCP 연동이 깨지던 문제 해소.
 */
@Component
public class SettingStore {

    private final PlatformSettingRepository repository;

    public SettingStore(PlatformSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * key가 있으면 그 값을, 없으면 supplier로 생성해 저장한 뒤 그 값을 돌려준다.
     * 기동 부트스트랩(단일 스레드)에서 부르므로 별도 락은 두지 않는다.
     */
    @Transactional
    public String getOrCreate(String key, Supplier<String> generator) {
        return repository.findById(key)
                .map(PlatformSetting::getValue)
                .orElseGet(() -> repository.save(new PlatformSetting(key, generator.get())).getValue());
    }
}
