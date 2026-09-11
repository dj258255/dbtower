package io.dbtower.workbench.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI 출력에서 첫 번째 균형 잡힌 JSON 객체를 뽑는다. headless CLI 백엔드는 지시해도 산문·코드펜스로 감쌀 때가 있어,
 * 형식이 조금 어긋났다고 제안 자체를 버리지 않기 위해서다(DiagnosisService의 추출과 같은 규칙).
 */
final class JsonExtract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtract() {
    }

    static JsonNode firstObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchBrace(text, start);
            if (end > start) {
                try {
                    JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
                    if (node != null && node.isObject()) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // 다음 '{' 후보로 넘어간다
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /** open 위치의 '{'와 짝을 이루는 '}'의 인덱스 — 문자열 리터럴 안의 중괄호(SQL·명령 JSON)는 무시한다. */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
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
