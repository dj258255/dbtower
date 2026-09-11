package io.dbtower.insight.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 평문 조각 묶음 — 묶여 나가도 이어 붙이면 원문과 한 글자도 다르지 않아야 한다(143절). */
class TextDeltaBatcherTest {

    @Test
    void 간격_안의_조각은_묶이고_flush가_남은_조각을_빠짐없이_보낸다() {
        List<String> sent = new ArrayList<>();
        TextDeltaBatcher batcher = new TextDeltaBatcher(sent::add, Long.MAX_VALUE / 2);

        batcher.accept("가장 ");   // 첫 조각은 바로 나간다(기다리는 화면에 처음 글자를 빨리 보이게)
        batcher.accept("유력한 ");
        batcher.accept("원인은");
        batcher.flush();

        assertThat(sent).containsExactly("가장 ", "유력한 원인은");
        assertThat(String.join("", sent)).isEqualTo("가장 유력한 원인은");
    }

    @Test
    void 간격이_0이면_조각마다_보내고_빈_조각과_빈_flush는_보내지_않는다() {
        List<String> sent = new ArrayList<>();
        TextDeltaBatcher batcher = new TextDeltaBatcher(sent::add, 0);

        batcher.accept("a");
        batcher.accept("");
        batcher.accept(null);
        batcher.accept("b");
        batcher.flush();

        assertThat(sent).containsExactly("a", "b");
    }
}
