package io.dbtower.security.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 토큰 재시작 생존의 핵심 — SettingStore.getOrCreate는 "있으면 읽고 없으면 만든다".
 * 두 번째 호출의 generator는 무시되고 저장된 값이 나와야, 재시작마다 토큰이 바뀌지 않는다.
 */
@DataJpaTest
@Import(SettingStore.class)
class SettingStorePersistenceTest {

    @Autowired
    SettingStore store;

    @Test
    void 있으면_읽고_없으면_만들어_저장한다() {
        String first = store.getOrCreate("api-token", () -> "FIRST");
        // 두 번째 호출은 새 값이 아니라 저장된 값을 돌려준다(generator 무시) — 재시작 시뮬레이션
        String second = store.getOrCreate("api-token", () -> "SECOND-should-be-ignored");

        assertThat(first).isEqualTo("FIRST");
        assertThat(second).isEqualTo("FIRST");
    }

    @Test
    void 다른_키는_독립적으로_생성된다() {
        assertThat(store.getOrCreate("a", () -> "va")).isEqualTo("va");
        assertThat(store.getOrCreate("b", () -> "vb")).isEqualTo("vb");
        assertThat(store.getOrCreate("a", () -> "x")).isEqualTo("va");
    }
}
