package io.dbtower.aiops.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델 소견의 수치와 인용을 DBTower가 모은 사실과 대조한다.
 *
 * <p>의미를 이해하려는 검사가 아니다. "소견에 적힌 숫자가 사실·규칙·참고 자료·요청 문장 어딘가에 글자로 있는가"와
 * "F3·G2·R1 같은 인용 번호가 실제로 존재하는가"만 본다. 숫자가 섞인 식별자(쿼리 다이제스트 등)는 사실 목록에 나온 것만
 * 수치가 아니라고 본다 — 사실에 없는 식별자 속 숫자는 그대로 대조 대상이다. 모델이 두 수를 나눠 "4.3배"를 만들면 계산이 맞아도
 * 검증 안 됨으로 남는다 — 운영자가 확인할 수 없는 수치를 확인된 사실처럼 보여주지 않는 쪽을 택했다.
 * 대신 거부하지 않고 목록으로 남겨, 소견 자체는 읽을 수 있게 둔다.</p>
 */
final class ClaimVerifier {

    // 앞이 영문자·숫자·밑줄·점이면 식별자나 소수의 일부라 새 수치로 보지 않는다. 뒤에는 단위(ms, %, 시간)가 붙을 수 있어 막지 않는다 —
    // 처음엔 뒤의 영문자도 막았는데, 그러면 "350ms"처럼 단위가 붙은 수치를 통째로 놓쳐 지어낸 값이 검증을 통과했다
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?(?![0-9])");
    /**
     * 숫자가 섞인 식별자(쿼리 다이제스트 3fa2b9, 인스턴스 이름 pg16) — 사실에 나온 것만 대조 전에 지운다.
     * 영문자 뒤에 숫자가 오는 토큰만 식별자다. "숫자 뒤 영문자"까지 넣으면 48.5ms의 5ms가 식별자로 잘려 수치가 48로 쪼개졌다.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]*[A-Za-z_][0-9][A-Za-z0-9_]*");
    private static final Pattern CITATION = Pattern.compile("(?<![A-Za-z0-9_])([FGR])(\\d+)(?![A-Za-z0-9_])");
    /**
     * 시각 — 날짜·시각을 숫자 여러 개로 쪼개면 안 된다. 실측(169절)에서 모델이 분석 구간 "2026-09-15T16:49:11Z"를 인용하자
     * 2026·15·49·17·9가 따로 "사실에 없는 수치"로 잡혀 정직한 답변에 경고가 여섯 줄 붙었다.
     */
    private static final Pattern TIME = Pattern.compile(
            "(?<![0-9])(?:(\\d{4}-\\d{2}-\\d{2})(?:[T ](\\d{2}:\\d{2})(:\\d{2})?(?:\\.\\d+)?)?"
                    + "|(\\d{1,2}:\\d{2})(:\\d{2})?(?:\\.\\d+)?)(?:Z|[+-]\\d{2}:?\\d{2})?(?![0-9])");
    /** "1차 분석", "2단계"처럼 순서를 말하는 한 자리 수는 수치 주장이 아니다 */
    private static final Pattern ORDINAL_SUFFIX = Pattern.compile("^\\s?(차|단계|번째|순위)");

    private ClaimVerifier() {
    }

    /**
     * @param platformContext 사실 목록은 아니지만 DBTower가 만든 글 — 분석 구간, 수집 중 생긴 불확실성. 모델이 인용해도 지어낸 값이 아니다
     */
    static List<String> verify(String opinion, List<String> evidence, List<String> nextActions,
                               List<String> facts, List<String> rules, List<String> references, String prompt,
                               List<String> platformContext) {
        Set<String> known = new HashSet<>();
        Set<String> knownTimes = new HashSet<>();
        Set<String> identifiers = new HashSet<>();
        for (List<String> source : List.of(facts, rules, references, platformContext, List.of(prompt == null ? "" : prompt))) {
            for (String line : source) {
                knownTimes.addAll(timesIn(line));
                known.addAll(numbersIn(withoutTimes(line)));
                Matcher id = IDENTIFIER.matcher(line);
                while (id.find()) {
                    identifiers.add(id.group());
                }
            }
        }
        Set<String> problems = new LinkedHashSet<>();
        List<String> claims = new ArrayList<>();
        if (opinion != null) {
            claims.add(opinion);
        }
        claims.addAll(evidence);
        claims.addAll(nextActions);
        for (String claim : claims) {
            for (String t : timeClaims(claim)) {
                if (!knownTimes.contains(t)) {
                    problems.add("사실 목록에 없는 시각: " + t);
                }
            }
            for (String n : numbersIn(withoutIdentifiers(withoutCitations(withoutTimes(claim)), identifiers))) {
                if (!known.contains(n)) {
                    problems.add("사실 목록에 없는 수치: " + n);
                }
            }
            Matcher m = CITATION.matcher(claim);
            while (m.find()) {
                int index = Integer.parseInt(m.group(2));
                int size = switch (m.group(1)) {
                    case "F" -> facts.size();
                    case "G" -> rules.size();
                    default -> references.size();
                };
                if (index < 1 || index > size) {
                    problems.add("존재하지 않는 인용: " + m.group(1) + index);
                }
            }
        }
        for (String e : evidence) {
            if (!CITATION.matcher(e).find()) {
                problems.add("인용 번호가 없는 근거: " + (e.length() > 80 ? e.substring(0, 80) + "..." : e));
            }
        }
        return List.copyOf(problems);
    }

    private static String withoutIdentifiers(String text, Set<String> identifiers) {
        Matcher m = IDENTIFIER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, identifiers.contains(m.group()) ? " " : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 알려진 시각 — 날짜와 시각을 따로도 넣는다. 모델은 "2026-09-15T16:49:11Z ~ 17:49:11Z"처럼 뒤쪽 날짜를 흔히 생략한다 */
    static Set<String> timesIn(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = TIME.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(1));
                if (m.group(2) != null) {
                    String hms = m.group(2) + (m.group(3) == null ? "" : m.group(3));
                    out.add(m.group(1) + "T" + hms);
                    out.add(hms);
                    out.add(m.group(2));
                }
            } else {
                String hms = m.group(4) + (m.group(5) == null ? "" : m.group(5));
                out.add(hms);
                out.add(m.group(4));
            }
        }
        return out;
    }

    /** 주장 속 시각은 가장 긴 형태 하나로 본다(소수 초와 시간대 표기는 버린다) */
    private static List<String> timeClaims(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TIME.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(2) == null ? m.group(1)
                        : m.group(1) + "T" + m.group(2) + (m.group(3) == null ? "" : m.group(3)));
            } else {
                out.add(m.group(4) + (m.group(5) == null ? "" : m.group(5)));
            }
        }
        return out;
    }

    private static String withoutTimes(String text) {
        return TIME.matcher(text).replaceAll(" ");
    }

    private static String withoutCitations(String text) {
        return CITATION.matcher(text).replaceAll(" ");
    }

    /** 표기 차이(천 단위 쉼표, 끝자리 0)를 지운 정규형으로 모은다 — "1,200"과 "1200", "30.50"과 "30.5"는 같은 수다 */
    static Set<String> numbersIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String whole = m.group(1).replace(",", "");
            if (m.group(2) == null && whole.length() == 1
                    && ORDINAL_SUFFIX.matcher(text.substring(m.end())).find()) {
                continue;
            }
            String raw = whole + (m.group(2) == null ? "" : m.group(2));
            out.add(normalize(raw));
        }
        return out;
    }

    private static String normalize(String raw) {
        BigDecimal value = new BigDecimal(raw).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }
}
