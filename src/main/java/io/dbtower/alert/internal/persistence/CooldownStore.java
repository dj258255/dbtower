package io.dbtower.alert.internal.persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경보 쿨다운의 마지막 전송 시각을 두는 곳. 판정(쿨다운 안인가)은 CooldownGate가 하고, 여기는 시각만 읽고 쓴다.
 *
 * <p>인메모리 구현을 남기는 이유: 감지기 테스트가 생성자를 직접 부르고, 저장소 없이도 감지 규칙 자체는 그대로 검증돼야 한다.
 * 운영에서는 {@link JdbcCooldownStore}가 붙는다 — 재기동해도 쿨다운이 이어진다.</p>
 */
public interface CooldownStore {

    /** 마지막으로 전송에 성공한 시각. 없으면 null */
    LocalDateTime lastAlerted(String key);

    /** 전송에 성공한 키들의 시각을 기록한다(있으면 덮는다) */
    void record(Collection<String> keys, LocalDateTime at);

    /** 접두로 시작하는 키를 지운다 — 인스턴스 삭제 시 "감지기:인스턴스id:" 접두로 부른다 */
    void evictPrefix(String prefix);

    /** 접두로 시작하되 기준보다 오래된 키를 지운다 — 쿨다운이 끝난 행은 판정에 쓰이지 않는다 */
    void pruneBefore(String prefix, LocalDateTime cutoff);

    static CooldownStore inMemory() {
        return new InMemory();
    }

    final class InMemory implements CooldownStore {

        private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

        @Override
        public LocalDateTime lastAlerted(String key) {
            return lastAlerted.get(key);
        }

        @Override
        public void record(Collection<String> keys, LocalDateTime at) {
            keys.forEach(k -> lastAlerted.put(k, at));
        }

        @Override
        public void evictPrefix(String prefix) {
            lastAlerted.keySet().removeIf(k -> k.startsWith(prefix));
        }

        @Override
        public void pruneBefore(String prefix, LocalDateTime cutoff) {
            lastAlerted.entrySet().removeIf(e -> e.getKey().startsWith(prefix) && e.getValue().isBefore(cutoff));
        }
    }
}
