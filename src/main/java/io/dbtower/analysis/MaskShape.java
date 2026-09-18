package io.dbtower.analysis;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가린 값이 남길 <b>모양</b> — 값 자체는 지우고 진단이 기대는 성질만 남긴다.
 *
 * <p>왜 모양이 필요한가: 조건 값이 진단을 가르는 사례가 있다. 앞 와일드카드는 인덱스를 못 쓰는 이유
 * 그 자체이고({@code '%@gmail.com'}), 숫자의 크기는 선택도를 가르며({@code amount > 10} 대 {@code > 99000}),
 * 날짜가 데이터 시작보다 이른지는 풀스캔이 정상인지를 가른다({@code created_at >= '2000-01-01'}).
 * 값을 통째로 {@code ?}로 지우면 이 셋이 모두 사라진다.
 *
 * <p>그래서 {@link AiMaskLevel#STRUCTURE}는 값을 지우되 {@code %} 자리, 자릿수, 지금으로부터의 거리를
 * 남긴다. 남는 것은 값이 아니라 값의 성질이라 원문을 복원할 수 없다.
 */
final class MaskShape {

    /** {@code 2026-08-01} · {@code 2026/08/01} — 시각이 붙어도 날짜 부분만 본다. */
    private static final Pattern DATE =
            Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[ T].*)?");

    private MaskShape() {
    }

    /** 따옴표 안에 들어갈 몸통 하나. 앞뒤 {@code %}·정규식 앵커·날짜 거리를 남긴다. */
    static String body(String value, AiMaskLevel level, Clock clock) {
        if (level == AiMaskLevel.FULL || value == null) {
            return "?";
        }
        String date = relativeDate(value, clock);
        if (date != null) {
            return "?(" + date + ")";
        }
        String lead = value.startsWith("%") ? "%" : value.startsWith("^") ? "^" : "";
        String trail = value.length() > 1 && value.endsWith("%") ? "%"
                : value.length() > 1 && value.endsWith("$") ? "$" : "";
        return lead + "?" + trail;
    }

    /**
     * 숫자 리터럴 하나 → {@code ?(5자리)}. 크기를 자릿수로만 남기는 이유는 선택도 판단에 필요한 것이
     * 값이 아니라 규모이기 때문이다. 16진수·지수 표기는 자릿수가 규모를 뜻하지 않아 통째로 지운다.
     */
    static String number(String literal, AiMaskLevel level) {
        if (level == AiMaskLevel.FULL || literal == null || literal.isBlank()) {
            return "?";
        }
        String digits = literal.startsWith("-") || literal.startsWith("+") ? literal.substring(1) : literal;
        if (digits.startsWith("0x") || digits.startsWith("0X")
                || digits.contains("e") || digits.contains("E")) {
            return "?";
        }
        int dot = digits.indexOf('.');
        String integer = dot < 0 ? digits : digits.substring(0, dot);
        String stripped = integer.replaceFirst("^0+(?=\\d)", "");
        if (stripped.isEmpty() || !stripped.chars().allMatch(Character::isDigit)) {
            return "?";
        }
        return "?(" + stripped.length() + "자리" + (dot < 0 ? "" : " 소수") + ")";
    }

    /**
     * 날짜면 지금으로부터의 거리, 아니면 {@code null}.
     *
     * <p>거리를 남기는 이유는 "언제인가"가 아니라 "얼마나 오래됐는가"가 선택도를 가르기 때문이다.
     * 근사로 뭉개므로 원래 날짜는 복원되지 않는다.
     */
    static String relativeDate(String value, Clock clock) {
        Matcher m = DATE.matcher(value);
        if (!m.matches()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.of(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (Exception invalid) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(date, LocalDate.now(clock));
        long abs = Math.abs(days);
        String direction = days >= 0 ? "전" : "후";
        if (abs <= 1) {
            return "오늘 무렵";
        }
        if (abs < 31) {
            return "약 " + abs + "일 " + direction;
        }
        if (abs < 365) {
            return "약 " + Math.round(abs / 30.0) + "개월 " + direction;
        }
        return "약 " + Math.round(abs / 365.0) + "년 " + direction;
    }
}
