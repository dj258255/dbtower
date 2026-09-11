package io.dbtower.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * claude CLI를 부르는 자식 프로세스 경로(148절 감사) — claude 대신 sh로 같은 runCli를 돌린다.
 * 예전 callCli는 stdout을 다 읽은 뒤에 시간 초과를 재고 stderr를 읽지 않아, 멈춘 자식이나 stderr를 많이 쓰는 자식 앞에서 끝나지 않았다.
 */
@DisabledOnOs(OS.WINDOWS)
class AiAnalyzerCliProcessTest {

    @Test
    @Timeout(20)
    void stderr를_많이_써도_막히지_않고_stdout을_받는다() throws Exception {
        // 파이프 버퍼(보통 64KB)의 30배를 stderr에 쓴 뒤 stdout에 결과를 쓴다 — stderr를 비우지 않으면 쓰기에서 멈춘다
        String out = AiAnalyzer.runCli(List.of("sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' e >&2; printf ok"),
                "", 15, null);
        assertThat(out).isEqualTo("ok");
    }

    @Test
    @Timeout(20)
    void 멈춘_자식은_제한_시간에_끊긴다() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> AiAnalyzer.runCli(List.of("sh", "-c", "sleep 60"), "", 2, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("시간 초과");
        assertThat((System.nanoTime() - start) / 1_000_000_000.0).isLessThan(10);
    }

    @Test
    @Timeout(20)
    void 실패하면_stderr_뒤쪽을_사유로_싣는다() {
        assertThatThrownBy(() -> AiAnalyzer.runCli(
                List.of("sh", "-c", "head -c 100000 /dev/zero | tr '\\0' x >&2; echo 'last reason' >&2; exit 3"), "", 15, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 코드 3")
                .hasMessageEndingWith("last reason");
    }

    @Test
    @Timeout(20)
    void 입력은_stdin으로_넣고_줄마다_넘긴다() throws Exception {
        List<String> lines = new ArrayList<>();
        AiAnalyzer.runCli(List.of("sh", "-c", "cat; printf '\\nend\\n'"), "첫 줄", 15, lines::add);
        assertThat(lines).containsExactly("첫 줄", "end");
    }

    @Test
    void 뒤쪽_8KB만_남긴다() {
        byte[] data = ("A".repeat(20_000) + "TAIL").getBytes(StandardCharsets.UTF_8);
        String tail = AiAnalyzer.drainTail(new ByteArrayInputStream(data));
        assertThat(tail).hasSize(8_192).endsWith("TAIL");
    }
}
