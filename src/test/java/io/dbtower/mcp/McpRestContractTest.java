package io.dbtower.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 도구 ↔ REST 매핑 계약.
 *
 * <p>McpProtocolHandler의 도구들은 자기 자신의 REST를 <b>문자열 URL</b>로 호출한다. 채널 계층에
 * 비즈니스 로직을 두지 않는다는 원칙에는 100% 충실하지만, 대가로 컴파일 타임 결합이 0이다 —
 * REST 경로를 하나 리네임하면 <b>어떤 테스트도 실패하지 않고</b> MCP와 Discord/Slack 봇이
 * 런타임에 깨진다. 다관점 리뷰에서 "리팩토링 안전망의 가장 큰 구멍"으로 지목된 지점이다.
 *
 * <p>여기서는 McpProtocolHandler 소스에서 도구가 호출하는 URL 템플릿을 뽑아, Spring에 실제로
 * 등록된 핸들러 매핑에 존재하는지 대조한다. 소스를 읽는 방식이 우아하진 않지만, URL이 람다 안에서
 * 문자열 결합으로 만들어져 실행 없이는 얻을 수 없고, 이 계약을 지키는 다른 자동 수단이 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpRestContractTest {

    /** URL 조각은 /, ?, & 로 시작한다 — args.get("instanceId") 같은 인자 이름 리터럴과 구분하는 규칙. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CALL = Pattern.compile("\\b(get|post)\\(\\s*\"/", Pattern.MULTILINE);

    // actuator가 controllerEndpointHandlerMapping을 하나 더 등록하므로 한정자가 필요하다
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void MCP_도구가_호출하는_REST_경로가_전부_실재한다() throws Exception {
        Set<String> registered = new LinkedHashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            info.getPathPatternsCondition().getPatterns()
                    .forEach(p -> registered.add(normalize(p.getPatternString())));
        }
        assertThat(registered).as("등록된 REST 매핑이 있어야 한다").isNotEmpty();

        Set<String> toolPaths = extractToolPaths();
        assertThat(toolPaths).as("McpProtocolHandler에서 도구 URL을 추출하지 못했다 — 추출 규칙이 낡았는지 확인").hasSizeGreaterThan(10);

        assertThat(toolPaths)
                .as("MCP 도구가 부르는 경로가 REST에 없다 — 경로를 리네임했다면 McpProtocolHandler도 같이 고쳐야 한다")
                .allSatisfy(p -> assertThat(registered).contains(p));
    }

    /** McpProtocolHandler 소스에서 get(...)/post(...) 호출의 URL 템플릿을 뽑는다. */
    private Set<String> extractToolPaths() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/io/dbtower/mcp/McpProtocolHandler.java"));
        Set<String> paths = new LinkedHashSet<>();
        Matcher call = CALL.matcher(src);
        while (call.find()) {
            String arg = argumentText(src, call.end() - 2);   // 여는 따옴표 위치부터
            paths.add(normalize(buildTemplate(arg)));
        }
        return paths;
    }

    /** 호출 인자 구간을 괄호 깊이로 잘라낸다(문자열 안의 괄호는 세지 않는다). */
    private static String argumentText(String src, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return src.substring(from, i);
            }
        }
        return src.substring(from);
    }

    /**
     * URL 조각(/, ?, & 로 시작하는 리터럴)만 이어 붙이고 그 사이는 경로 변수로 본다.
     * 쿼리스트링은 매핑 대조 대상이 아니라 첫 ? 또는 &에서 자른다.
     */
    private static String buildTemplate(String argument) {
        StringBuilder url = new StringBuilder();
        Matcher m = STRING_LITERAL.matcher(argument);
        int prevEnd = -1;
        while (m.find()) {
            String lit = m.group(1);
            if (lit.isEmpty() || !(lit.startsWith("/") || lit.startsWith("?") || lit.startsWith("&"))) {
                continue;   // args.get("instanceId") 같은 인자 이름 — URL이 아니다
            }
            if (prevEnd >= 0) {
                // 두 조각 사이에 + 와 공백 말고 뭔가 있었으면 그 자리에 값이 들어간다 = 경로 변수.
                // 그냥 이어 붙인 리터럴("/compare" + "?baseFrom=")에는 자리표시자를 넣지 않는다.
                String gap = argument.substring(prevEnd, m.start()).replace("+", "").trim();
                if (!gap.isEmpty()) {
                    url.append("{}");
                }
            }
            url.append(lit);
            prevEnd = m.end();
        }
        String s = url.toString();
        int cut = s.indexOf('?');
        if (cut < 0) {
            cut = s.indexOf('&');
        }
        return cut >= 0 ? s.substring(0, cut) : s;
    }

    /** {id}·{}·{name} 을 전부 같은 자리표시자로 눕혀 비교 가능하게 만든다. */
    private static String normalize(String pattern) {
        return pattern.replaceAll("\\{[^}]*}", "{}");
    }
}
