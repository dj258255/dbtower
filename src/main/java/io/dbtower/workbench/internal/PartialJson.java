package io.dbtower.workbench.internal;

/**
 * 아직 다 오지 않은 JSON에서 문자열 필드의 "지금까지 온 앞부분"을 꺼낸다 — AI 응답 스트리밍 전용.
 *
 * <p>워크벤치 AI는 JSON 객체 하나로 답한다(형식이 있어야 SQL·가정·제목을 기계가 나눠 저장한다). 그 토큰을 그대로
 * 화면에 흘리면 사람은 따옴표와 역슬래시를 읽게 된다. 그래서 형식은 유지하고, 흘러오는 동안에는 설명과 SQL 값만
 * 뽑아 보여준다. 완성본 파싱은 여전히 {@code JsonExtract}가 한다 — 여기서 틀려도 저장되는 답은 바뀌지 않는다.
 */
final class PartialJson {

    private PartialJson() {
    }

    /**
     * @return 필드 값의 앞부분. 키가 아직 안 왔거나, 값이 문자열이 아니거나(null 포함) 따옴표가 아직 안 열렸으면 null
     */
    static String stringPrefix(String buffer, String field) {
        String key = "\"" + field + "\"";
        int from = 0;
        while (true) {
            int k = buffer.indexOf(key, from);
            if (k < 0) {
                return null;
            }
            int i = skipWhitespace(buffer, k + key.length());
            if (i >= buffer.length()) {
                return null;
            }
            // 값 자리에 같은 글자가 온 경우("title": "sql") — 키가 아니므로 계속 찾는다
            if (buffer.charAt(i) != ':') {
                from = k + 1;
                continue;
            }
            i = skipWhitespace(buffer, i + 1);
            if (i >= buffer.length() || buffer.charAt(i) != '"') {
                return null;
            }
            return readString(buffer, i + 1);
        }
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 끝 따옴표를 만나거나 버퍼가 끝날 때까지 읽는다. 반쯤 온 이스케이프는 다음 조각을 기다리며 버린다. */
    private static String readString(String s, int i) {
        StringBuilder out = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= s.length()) {
                break;
            }
            char e = s.charAt(i + 1);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 6 > s.length()) {
                        return out.toString();
                    }
                    try {
                        out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException ex) {
                        return out.toString();
                    }
                    i += 6;
                    continue;
                }
                default -> out.append(e); // \" \\ \/
            }
            i += 2;
        }
        return out.toString();
    }
}
