package io.dbtower;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * AI 응답을 화면으로 흘리는 작업 스레드 — 워크벤치 AI 보조(workbench)와 자연어 진단(mcp)이 함께 쓴다(VERIFICATION 141절).
 *
 * <p><b>왜 루트 패키지인가.</b> 두 모듈이 같은 상한을 나눠 써야 한다. 한쪽 모듈에 두면 다른 쪽이 그 모듈을 참조하게 되어
 * Modulith 경계가 꼬인다(SchedulingConfig와 같은 이유).
 *
 * <p><b>줄 세우지 않는다.</b> AI 한 턴은 수십 초라, 자리가 없을 때 대기열에 넣으면 사람은 "기다리는 중"과 "멈춤"을 구분할 수
 * 없다. 자리가 없으면 바로 거절하고 화면이 그 사실을 말하게 한다.
 *
 * <p><b>인증을 넘긴다.</b> 스트림 작업은 요청 스레드가 아닌 곳에서 돈다. 팀 범위·워크시트 소유·감사 주체가 전부
 * SecurityContext에서 나오므로, 제출하는 순간의 컨텍스트를 작업에 실어 보낸다. 안 실으면 작업은 인증 없는 전역
 * 주체(폴러와 같은 권한)로 돌아 팀 범위가 풀린다.
 */
@Component
public class AiStreamExecutor {

    private final ExecutorService pool;
    private final Semaphore slots;

    public AiStreamExecutor(@Value("${dbtower.ai.stream-concurrency:4}") int concurrency) {
        int size = Math.max(1, concurrency);
        this.slots = new Semaphore(size);
        this.pool = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "ai-stream");
            t.setDaemon(true);
            return t;
        });
    }

    /** @return 자리가 없어 거절했으면 false. 호출 스레드의 SecurityContext가 작업에 그대로 실린다 */
    public boolean trySubmit(Runnable task) {
        if (!slots.tryAcquire()) {
            return false;
        }
        Runnable withContext = new DelegatingSecurityContextRunnable(() -> {
            try {
                task.run();
            } finally {
                slots.release();
            }
        });
        try {
            pool.execute(withContext);
            return true;
        } catch (RejectedExecutionException e) {
            slots.release();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
