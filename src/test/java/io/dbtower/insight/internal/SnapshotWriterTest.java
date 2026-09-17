package io.dbtower.insight.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotWriterTest {

    @Test
    void 열_길이_안의_문장은_그대로_둔다() {
        String sql = "SELECT 1";
        assertThat(SnapshotWriter.fitQueryText(sql)).isSameAs(sql);
        assertThat(SnapshotWriter.fitQueryText(null)).isNull();
        String exact = "x".repeat(SnapshotWriter.QUERY_TEXT_MAX);
        assertThat(SnapshotWriter.fitQueryText(exact)).isSameAs(exact);
    }

    @Test
    void 열_길이를_넘는_문장은_열_길이로_자른다() {
        // #70에서 수집을 멈춘 모양 — id를 수십 개 OR로 이은 문장
        StringBuilder sb = new StringBuilder("SELECT * FROM exp_change_lock WHERE (\"id\" = $1)");
        for (int i = 2; sb.length() <= 4500; i++) {
            sb.append(" OR (\"id\" = $").append(i).append(')');
        }
        String fitted = SnapshotWriter.fitQueryText(sb.toString());
        assertThat(fitted).hasSize(SnapshotWriter.QUERY_TEXT_MAX);
        assertThat(sb.toString()).startsWith(fitted);
    }

    @Test
    void 자르는_자리가_서로게이트_쌍_가운데면_한_글자_앞에서_끊는다() {
        String text = "a".repeat(SnapshotWriter.QUERY_TEXT_MAX - 1) + "😀" + "tail";
        String fitted = SnapshotWriter.fitQueryText(text);
        assertThat(fitted).hasSize(SnapshotWriter.QUERY_TEXT_MAX - 1);
        assertThat(Character.isHighSurrogate(fitted.charAt(fitted.length() - 1))).isFalse();
    }
}
