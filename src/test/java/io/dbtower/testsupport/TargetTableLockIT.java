package io.dbtower.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자문 락이 실제로 뒤에 온 실행을 기다리게 하는지 확인한다(#112).
 *
 * <p>락이 "잡히기는 하는데 막지는 않는" 상태면 아무 소용이 없다. 그래서 잡은 채로 두 번째 요청을 내보고
 * 그것이 <b>끝나지 않는지</b>를 본다. 그 뒤 풀고 나서야 끝나는 것까지 확인한다.
 *
 * <p>{@code DBTOWER_CONSOLE_IT=1 ./gradlew test --tests '*TargetTableLockIT'}
 */
class TargetTableLockIT {

    private static final String GATE = "DBTOWER_CONSOLE_IT";

    private static List<TargetTableLock.Target> targets() {
        return List.of(
                new TargetTableLock.Target("jdbc:mysql://127.0.0.1:13306/sample", "root", "dbtower1234"),
                new TargetTableLock.Target("jdbc:postgresql://127.0.0.1:15432/sample", "postgres", "dbtower1234"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("락을 쥔 동안 두 번째 요청은 끝나지 않고, 풀면 그제야 끝난다")
    void secondAcquireWaitsUntilReleased() throws Exception {
        CompletableFuture<TargetTableLock> second;
        try (TargetTableLock first = TargetTableLock.acquire(targets())) {
            second = CompletableFuture.supplyAsync(() -> TargetTableLock.acquire(targets()));

            // 잡고 있는 동안에는 끝나면 안 된다 — 끝난다면 락이 막지 못하는 것이다
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
                    .as("락을 쥔 동안 두 번째 요청이 끝났다 — 막지 못하고 있다")
                    .isInstanceOf(TimeoutException.class);
        }

        // 풀렸으니 이제 잡혀야 한다
        try (TargetTableLock acquired = second.get(30, TimeUnit.SECONDS)) {
            assertThat(acquired).isNotNull();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("연결이 닫히면 락도 풀린다 — 테스트가 중간에 죽어도 락이 남지 않는다")
    void closingReleases() throws Exception {
        TargetTableLock first = TargetTableLock.acquire(targets());
        first.close();

        // 같은 JVM이 아니라 새 세션으로 다시 잡아 본다. 남아 있었다면 여기서 멈춘다
        CompletableFuture<TargetTableLock> again = CompletableFuture.supplyAsync(() -> TargetTableLock.acquire(targets()));
        try (TargetTableLock acquired = again.get(30, TimeUnit.SECONDS)) {
            assertThat(acquired).isNotNull();
        }
    }
}
