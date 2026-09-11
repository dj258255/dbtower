package io.dbtower.insight.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.analysis.TextDeltaBatcher;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 쿼리 상세 "AI 분석" 한 번 — EXPLAIN, 규칙 지적, AI 1차 분석(VERIFICATION 143절).
 *
 * <p>한 번에 받는 REST와 흘려 받는 SSE가 같은 순서·같은 프롬프트를 쓰도록 여기 한 곳에 둔다. 두 경로가 따로 프롬프트를 만들면
 * 화면에서 본 분석과 문의에 첨부된 분석이 다른 입력에서 나온다.
 *
 * <p>순서가 곧 설계다: 실행계획과 규칙 지적은 AI를 기다리지 않고 먼저 알린다. 계획은 1초 안에 오고 AI 답은 수십 초 걸리는데,
 * 사람이 먼저 봐야 하는 근거는 계획이다. AI는 판단자가 아니라 그 계획 위의 1차 분석기다.
 */
@Component
public class AiAnalysisRunner {

    public record Result(String plan, List<String> findings, String aiAnalysis) {
    }

    /** 흘려 받는 쪽의 알림. 한 번에 받는 REST는 {@link #NONE}이고, 그러면 AI도 스트리밍 호출을 쓰지 않는다. */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        default void plan(String plan, List<String> findings) {
        }

        default void text(String delta) {
        }
    }

    private final DbmsOperatorFactory operatorFactory;
    private final RuleBasedAnalyzer analyzer;
    private final AiAnalyzer aiAnalyzer;
    private final QueryMasker queryMasker;

    public AiAnalysisRunner(DbmsOperatorFactory operatorFactory, RuleBasedAnalyzer analyzer,
                            AiAnalyzer aiAnalyzer, QueryMasker queryMasker) {
        this.operatorFactory = operatorFactory;
        this.analyzer = analyzer;
        this.aiAnalyzer = aiAnalyzer;
        this.queryMasker = queryMasker;
    }

    public Result run(DatabaseInstance instance, String sql, Listener listener) {
        String plan = operatorFactory.create(instance).explain(sql);
        List<String> findings = analyzer.analyze(instance.getType(), plan);
        listener.plan(plan, findings);
        // AI 프롬프트 마스킹은 mask-ai-prompt(기본 false)로만 켠다 — 리터럴을 가리면
        // IN절 개수·상수 분포 같은 판정 정확도가 떨어지는 트레이드오프가 있어 명시적 선택.
        String context = """
                [%s] 아래 쿼리와 실행계획을 판단 기준에 따라 분석해줘.
                SQL:
                %s
                실행계획:
                %s
                규칙 기반 지적: %s""".formatted(instance.getType(), queryMasker.applyForAiPrompt(sql), plan,
                findings.isEmpty() ? "(없음)" : String.join(" / ", findings));
        Optional<String> ai;
        if (listener == Listener.NONE) {
            ai = aiAnalyzer.analyze(CallSite.EXPLAIN, context);
        } else {
            TextDeltaBatcher batcher = new TextDeltaBatcher(listener::text);
            ai = aiAnalyzer.analyzeStreaming(CallSite.EXPLAIN, context, batcher);
            batcher.flush();
        }
        return new Result(plan, findings, ai.orElse(null));
    }
}
