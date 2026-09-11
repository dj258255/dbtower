package io.dbtower.analysis;

import java.util.function.Consumer;

/**
 * AI 글자 조각을 모아 일정 간격으로 한 번에 내보낸다(VERIFICATION 143·146절).
 *
 * <p>조각은 몇 글자 단위로 온다. 조각마다 SSE 이벤트를 보내면 긴 분석 한 편에 이벤트가 수백 개가 되고 화면도 그만큼 다시 그린다.
 * 워크벤치는 미완성 JSON에서 값만 뽑느라 앞부분 전체를 다시 보냈지만({@code PartialRelay}), 평문 답은 새로 온 부분만 보낸다.
 * 간격 안에 남은 조각은 {@link #flush()}로 반드시 내보낸다 — 평문은 완성본과 이어 붙여 보이므로 빠진 조각이 곧 빠진 글자가 된다.
 *
 * <p>쿼리 상세 AI 분석(insight)·변경 요청 소견(review)·인시던트 리포트(alert)가 함께 쓰므로 AI 호출과 같은 공개 모듈에 둔다.
 */
public final class TextDeltaBatcher implements Consumer<String> {

    private final Consumer<String> sink;
    private final long minGapNanos;
    private final StringBuilder pending = new StringBuilder();
    private long lastFlush;

    public TextDeltaBatcher(Consumer<String> sink) {
        this(sink, 80_000_000L);
    }

    TextDeltaBatcher(Consumer<String> sink, long minGapNanos) {
        this.sink = sink;
        this.minGapNanos = minGapNanos;
        this.lastFlush = System.nanoTime() - minGapNanos;
    }

    @Override
    public synchronized void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        pending.append(chunk);
        long now = System.nanoTime();
        if (now - lastFlush >= minGapNanos) {
            emit(now);
        }
    }

    /** 남은 조각을 보낸다. 완성본을 보내기 직전에 부른다. */
    public synchronized void flush() {
        emit(System.nanoTime());
    }

    private void emit(long now) {
        if (pending.isEmpty()) {
            return;
        }
        String text = pending.toString();
        pending.setLength(0);
        lastFlush = now;
        sink.accept(text);
    }
}
