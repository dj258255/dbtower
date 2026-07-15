package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.mcp.McpProtocolHandler;
import io.dbtower.security.ApiTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 자연어 근본원인 진단 (Phase D3) — 단발 분석(AiAnalyzer)을 "도구 사용 루프"로 승격한다.
 *
 * 운영 사례("CPU 100% → AI가 모니터링+Wait Event 동시 조회 → 원인 진단")·pganalyze의
 * "AI-assisted but developer-driven" 모델을 따른다. 사람이 질문을 던지면, AI가 어떤 MCP 도구를
 * 어떤 인자로 부를지 스스로 정하고(JSON), 서버가 그 도구를 실제로 실행해(McpProtocolHandler →
 * 자기 REST) 결과를 다시 AI에 돌려주는 루프를 최대 N스텝 돈 뒤 근본원인을 종합한다.
 *
 * 오케스트레이션 방식: SDK 네이티브 tool-use 타입에 묶이지 않으려고 "JSON 도구 호출 프로토콜"을
 * 쓴다 — AI에게 매 턴 call_tool/final JSON 하나만 내라고 지시하고, 서버가 파싱해 실행한다.
 * 그래서 API 백엔드든 claude CLI headless든 같은 코드로 돈다(백엔드 무관).
 *
 * 정체성 가드레일: AI 루프에는 읽기 전용 도구만 노출한다(READ_ONLY_TOOLS). MCP 핸들러가 지금
 * 쓰기·파괴 도구(kill·backup·online-ddl)를 애초에 등록하지 않지만, 여기서 화이트리스트로 한 번 더
 * 못박아 나중에 누가 쓰기 도구를 추가해도 에이전트가 부를 수 없게 한다. 대상 DB 변경 0.
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    /**
     * AI 루프에 노출·실행을 허용하는 읽기 전용 도구 화이트리스트.
     * 이 목록에 없는 도구는 AI에게 보이지도 않고, 설령 요청해도 실행 전에 거부된다.
     */
    static final Set<String> READ_ONLY_TOOLS = Set.of(
            "list_instances", "health", "query_stats", "slow_queries", "compare",
            "activity", "explain", "wait_events", "replication", "sessions",
            "schema", "schema_diff");

    /** 도구 결과를 다음 프롬프트에 넣을 때 상한 — 큰 결과가 컨텍스트를 폭주시키지 않게 자른다. */
    private static final int OBSERVATION_CAP = 6000;

    /** AI 한 스텝(시스템 프롬프트 + 누적 대화 → 다음 결정) — 백엔드를 추상화한 시임(테스트 주입점). */
    @FunctionalInterface
    interface AiTurn {
        Optional<String> complete(String systemPrompt, String userMessage);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpProtocolHandler handler;
    private final AiTurn ai;
    private final boolean aiEnabled;
    private final QueryMasker queryMasker;
    private final String backend;
    private final Path rulesPath;
    private final int maxSteps;

    // Spring 생성자 — 실제 MCP 핸들러(자기 REST로 위임, 서비스 토큰 인증)와 AiAnalyzer 백엔드를 엮는다.
    @Autowired
    public DiagnosisService(@Value("${server.port:8080}") int port,
                            ApiTokenProvider tokens,
                            AiAnalyzer analyzer,
                            QueryMasker queryMasker,
                            @Value("${dbtower.ai.rules-path:docs/ai-analysis-rules.md}") String rulesPath,
                            @Value("${dbtower.ai.diagnose-max-steps:5}") int maxSteps) {
        this(new McpProtocolHandler("http://localhost:" + port, tokens.token()),
                analyzer::complete, analyzer.isEnabled(), analyzer.backend(), queryMasker, rulesPath, maxSteps);
    }

    // 테스트 생성자 — 스크립트된 AI와 (목 REST를 가리키는) 실제 MCP 핸들러를 주입해
    // 오케스트레이션(도구 연쇄·화이트리스트·최종 종합)을 AI 백엔드 없이 결정론적으로 검증한다.
    DiagnosisService(McpProtocolHandler handler, AiTurn ai, boolean aiEnabled,
                     String backend, QueryMasker queryMasker, String rulesPath, int maxSteps) {
        this.handler = handler;
        this.ai = ai;
        this.aiEnabled = aiEnabled;
        this.backend = backend;
        this.queryMasker = queryMasker;
        this.rulesPath = Path.of(rulesPath);
        this.maxSteps = Math.max(1, maxSteps);
    }

    /** 투명성용 — AI가 어떤 도구를 왜 불렀고 무엇을 봤는지(또는 왜 거부됐는지). */
    public record ToolCallTrace(int step, String tool, String arguments, String reason,
                                String resultSnippet, boolean rejected) {
    }

    public record DiagnosisResult(boolean aiEnabled, String backend, String question,
                                  String answer, String rootCause, String confidence,
                                  int toolCallCount, List<ToolCallTrace> toolCalls, String note) {
    }

    /**
     * 자연어 질문 → AI가 MCP 도구를 스스로 연쇄 호출해 근본원인을 서술한다.
     * AI 백엔드가 없으면(키·CLI 둘 다 없음) 정직하게 "비활성" 결과를 돌려준다.
     */
    public DiagnosisResult diagnose(long instanceId, String instanceType, String instanceName,
                                    String question) {
        if (!aiEnabled) {
            return new DiagnosisResult(false, backend, question, null, null, "none", 0, List.of(),
                    "AI 백엔드가 없습니다(ANTHROPIC_API_KEY 미설정 + claude CLI 없음) — 자연어 진단이 비활성입니다. "
                            + "개별 도구(비교·실행계획·대기 이벤트)는 웹 콘솔에서 직접 사용하세요.");
        }

        String systemPrompt = buildSystemPrompt(instanceId, instanceType, instanceName);
        StringBuilder transcript = new StringBuilder()
                .append("사용자 질문: ").append(question)
                .append("\n\n지금 첫 판단을 내려라. call_tool 또는 final JSON 하나만 출력한다.");
        List<ToolCallTrace> traces = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            Optional<String> out = ai.complete(systemPrompt, transcript.toString());
            if (out.isEmpty()) {
                return build(question, null, null, "low", traces,
                        "AI가 응답을 반환하지 못했습니다(백엔드 오류 또는 시간 초과). 부분 근거만 수집됨.");
            }
            JsonNode decision = extractJson(out.get());
            if (decision == null) {
                // JSON 형식이 아니면 그 텍스트를 최종 답변으로 간주(폴백) — 크래시보다 정직
                return build(question, out.get().trim(), null, "low", traces,
                        "AI가 형식 밖 텍스트를 반환해 그대로 최종 답변으로 처리했습니다.");
            }

            String action = decision.path("action").asText("");
            if ("final".equals(action)) {
                return build(question, textOrNull(decision, "answer"), textOrNull(decision, "rootCause"),
                        decision.path("confidence").asText("medium"), traces, null);
            }
            if (!"call_tool".equals(action)) {
                return build(question, decision.path("answer").asText(out.get().trim()),
                        null, "low", traces, "알 수 없는 action — 최종으로 처리했습니다.");
            }

            String tool = decision.path("tool").asText("");
            JsonNode arguments = decision.path("arguments");
            String reason = decision.path("reason").asText("");

            if (!READ_ONLY_TOOLS.contains(tool)) {
                // 읽기 전용 화이트리스트 밖 요청 — 실행하지 않고 거부 사유를 다시 AI에 알린다
                String msg = "거부됨: '" + tool + "'는 읽기 전용 화이트리스트에 없습니다. 허용 도구만 사용하라.";
                traces.add(new ToolCallTrace(step, tool, maskedArgs(arguments), reason, msg, true));
                transcript.append("\n\n[도구 호출 #").append(step).append("] ").append(tool)
                        .append(" → ").append(msg);
                log.warn("D3 진단 — 화이트리스트 밖 도구 요청 거부: {}", tool);
                continue;
            }

            String observation = callTool(tool, arguments);
            String snippet = observation.length() > OBSERVATION_CAP
                    ? observation.substring(0, OBSERVATION_CAP) + "…(생략)" : observation;
            traces.add(new ToolCallTrace(step, tool, maskedArgs(arguments), reason, snippet, false));
            log.info("D3 진단 step {} — tool={} args={} reason={}", step, tool, maskedArgs(arguments), reason);
            transcript.append("\n\n[도구 호출 #").append(step).append("] tool=").append(tool)
                    .append(" arguments=").append(maskedArgs(arguments))
                    .append("\n[결과]\n").append(snippet);
        }

        // 스텝 소진 — 지금까지 근거로 최종 종합을 강제한다
        transcript.append("\n\n최대 도구 호출 수에 도달했다. 더 부르지 말고, 지금까지의 근거만으로 반드시 "
                + "{\"action\":\"final\",...} 형식의 최종 답변을 내라.");
        Optional<String> last = ai.complete(systemPrompt, transcript.toString());
        if (last.isPresent()) {
            JsonNode d = extractJson(last.get());
            if (d != null && "final".equals(d.path("action").asText(""))) {
                return build(question, textOrNull(d, "answer"), textOrNull(d, "rootCause"),
                        d.path("confidence").asText("low"), traces,
                        "최대 스텝(" + maxSteps + ") 도달 후 종합했습니다.");
            }
            return build(question, last.get().trim(), null, "low", traces,
                    "최대 스텝(" + maxSteps + ") 도달 — 형식 밖 종합.");
        }
        return build(question, null, null, "low", traces,
                "최대 스텝(" + maxSteps + ") 도달, 최종 종합에 실패했습니다.");
    }

    private DiagnosisResult build(String question, String answer, String rootCause,
                                  String confidence, List<ToolCallTrace> traces, String note) {
        long executed = traces.stream().filter(t -> !t.rejected()).count();
        String conf = (confidence == null || confidence.isBlank()) ? "medium" : confidence;
        return new DiagnosisResult(true, backend, question, answer, rootCause, conf,
                (int) executed, List.copyOf(traces), note);
    }

    /** 도구 하나를 tools/call JSON-RPC로 실제 실행한다 — McpProtocolHandler를 그대로 재사용(같은 검증·위임 경로). */
    private String callTool(String tool, JsonNode arguments) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments == null || arguments.isMissingNode()
                ? mapper.createObjectNode() : arguments);
        ObjectNode resp = handler.handle(req);
        JsonNode content = resp.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("");
        }
        return resp.path("error").path("message").asText("(빈 결과)");
    }

    private String buildSystemPrompt(long instanceId, String type, String name) {
        return """
                당신은 DBTower의 DB 장애 근본원인 진단 에이전트다. 사용자의 자연어 질문에 답하기 위해,
                아래 "사용 가능한 도구"를 스스로 골라 여러 번 호출해 근거를 모은 뒤 근본원인을 서술한다.

                [행동 규약]
                - 매 턴 아래 두 형식 중 하나의 JSON 객체 "하나만" 출력한다. 그 밖의 텍스트·설명·코드펜스(```)는 절대 쓰지 않는다.
                  (1) 도구 호출: {"action":"call_tool","tool":"<도구이름>","arguments":{...},"reason":"지금 이 도구를 왜 부르는가"}
                  (2) 최종 답변: {"action":"final","answer":"근본원인과 근거를 한국어로 서술","rootCause":"한 줄 요약","confidence":"high|medium|low"}
                - 한 번에 도구 하나만 부른다. 결과를 받은 뒤 다음 판단을 한다.
                - 최소 2개 이상의 도구를 엮어 교차 검증한 근거로 결론을 낸다. 예: compare로 급증·신규 쿼리를 찾고 →
                  그 쿼리를 explain으로 실행계획 확인 → wait_events로 병목(IO/Lock)을 확인해 종합한다.
                - 근거가 없으면 지어내지 말고 confidence를 low로 두고 "확실치 않다/모른다"고 정직하게 답한다. 수치를 지어내지 않는다.
                - 도구의 instanceId 인자에는 항상 %d 를 쓴다. 시각 인자는 ISO LocalDateTime(예: 2026-07-03T15:20:30)로 준다.

                [사용 가능한 도구] — 전부 읽기 전용이다. 대상 DB를 바꾸는 도구(세션 종료·백업·스키마 변경)는 노출되지 않으며 요청해도 거부된다.
                %s
                [판단 기준 문서 — 반드시 이 기준에 근거해서만 판정한다]
                %s

                [대상] instanceId=%d, 기종=%s, 이름=%s. 현재 시각=%s.
                """.formatted(instanceId, toolCatalog(), loadRules(), instanceId, type, name, LocalDateTime.now());
    }

    /** MCP 핸들러의 tools/list에서 읽기 전용 도구만 골라 이름·설명·입력 스키마를 프롬프트용으로 나열한다. */
    private String toolCatalog() {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        ObjectNode resp = handler.handle(req);
        JsonNode toolsNode = resp.path("result").path("tools");
        StringBuilder sb = new StringBuilder();
        if (toolsNode.isArray()) {
            toolsNode.forEach(t -> {
                String toolName = t.path("name").asText();
                if (!READ_ONLY_TOOLS.contains(toolName)) {
                    return;
                }
                sb.append("- ").append(toolName).append(": ").append(t.path("description").asText())
                        .append("\n  inputSchema: ").append(t.path("inputSchema").toString()).append("\n");
            });
        }
        return sb.toString();
    }

    private String loadRules() {
        try {
            return Files.exists(rulesPath) ? Files.readString(rulesPath) : "";
        } catch (Exception e) {
            log.warn("판단 기준 문서 로드 실패: {}", e.getMessage());
            return "";
        }
    }

    private static String argsText(JsonNode arguments) {
        return arguments == null || arguments.isMissingNode() ? "{}" : arguments.toString();
    }

    /**
     * 응답·트랜스크립트에 에코되는 도구 인자 — sql 필드의 리터럴만 가린다(도구 실행 자체는 원문으로 —
     * EXPLAIN은 ?를 실행할 수 없다). 실행계획 결과(observation)는 가리지 않는다: rows·cost 숫자가
     * 진단의 본체라 리터럴 마스킹이 플랜을 훼손한다(트레이드오프를 숨기지 않고 명시).
     */
    private String maskedArgs(JsonNode arguments) {
        if (arguments != null && arguments.isObject() && arguments.path("sql").isTextual()) {
            ObjectNode copy = arguments.deepCopy();
            copy.put("sql", queryMasker.apply(arguments.get("sql").asText()));
            return copy.toString();
        }
        return argsText(arguments);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * AI 출력에서 첫 번째 균형 잡힌 JSON 객체 {...}를 뽑는다.
     * headless CLI가 산문·코드펜스로 감싸도 그 안의 결정 JSON을 안전하게 파싱하기 위함.
     */
    private JsonNode extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchBrace(text, start);
            if (end > start) {
                try {
                    return mapper.readTree(text.substring(start, end + 1));
                } catch (Exception ignored) {
                    // 다음 '{' 후보로
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /** open 위치의 '{'와 짝을 이루는 '}'의 인덱스 — 문자열 리터럴 안의 중괄호는 무시. */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
