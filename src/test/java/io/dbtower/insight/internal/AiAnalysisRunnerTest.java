package io.dbtower.insight.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 쿼리 상세 AI 분석의 순서(VERIFICATION 143절) — 계획과 규칙 지적은 AI를 기다리지 않고 먼저, AI 글은 흘려서, 완성본은 끝에.
 * 한 번에 받는 REST는 스트리밍 호출을 쓰지 않는다(기존 계약 그대로).
 */
class AiAnalysisRunnerTest {

    private static final String PLAN = "[{\"Plan\":{\"Node Type\":\"Seq Scan\"}}]";

    private final DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = mock(DbmsOperator.class);
    private final RuleBasedAnalyzer rules = mock(RuleBasedAnalyzer.class);
    private final AiAnalyzer ai = mock(AiAnalyzer.class);
    private final DatabaseInstance instance = new DatabaseInstance("pg", DbmsType.POSTGRESQL, "h", 5432, "sample", "u", "p");
    private AiAnalysisRunner runner;

    @BeforeEach
    void setUp() {
        when(factory.create(instance)).thenReturn(operator);
        when(operator.explain(anyString())).thenReturn(PLAN);
        when(rules.analyze(DbmsType.POSTGRESQL, PLAN)).thenReturn(List.of("Seq Scan 발생"));
        runner = new AiAnalysisRunner(factory, rules, ai, new QueryMasker(true, false));
    }

    @Test
    void 흘려_받으면_계획이_AI보다_먼저_오고_글은_이어_붙이면_완성본과_같다() {
        List<String> events = new ArrayList<>();
        when(ai.analyzeStreaming(eq(CallSite.EXPLAIN), anyString(), any())).thenAnswer(inv -> {
            events.add("ai-start");
            Consumer<String> onText = inv.getArgument(2);
            onText.accept("인덱스가 ");
            onText.accept("없다");
            return Optional.of("인덱스가 없다");
        });
        StringBuilder written = new StringBuilder();

        AiAnalysisRunner.Result r = runner.run(instance, "SELECT 1 FROM t WHERE a = $1", new AiAnalysisRunner.Listener() {
            @Override
            public void plan(String plan, List<String> findings) {
                events.add("plan:" + findings);
            }

            @Override
            public void text(String delta) {
                events.add("text");
                written.append(delta);
            }
        });

        assertThat(events.get(0)).isEqualTo("plan:[Seq Scan 발생]");
        assertThat(events.get(1)).isEqualTo("ai-start");
        assertThat(written.toString()).isEqualTo(r.aiAnalysis());
        assertThat(r.plan()).isEqualTo(PLAN);
        verify(ai, never()).analyze(any(), anyString());
    }

    @Test
    void 한_번에_받는_경로는_스트리밍_호출을_쓰지_않고_AI가_꺼져_있으면_분석만_비운다() {
        when(ai.analyze(eq(CallSite.EXPLAIN), anyString())).thenReturn(Optional.empty());

        AiAnalysisRunner.Result r = runner.run(instance, "SELECT 1", AiAnalysisRunner.Listener.NONE);

        assertThat(r.aiAnalysis()).isNull();
        assertThat(r.findings()).containsExactly("Seq Scan 발생");
        verify(ai, never()).analyzeStreaming(any(), anyString(), any());
    }
}
