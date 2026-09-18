package io.dbtower.analysis;

/**
 * AI로 보내는 SQL·실행계획의 가림 수준({@code dbtower.masking.ai-level}).
 *
 * <p>가리면 개인정보 노출은 줄지만 진단 근거도 함께 사라진다. 값이 진단을 가르는 사례로 잰 결과는
 * {@code docs/experiments/ai-masking-*}에 있다. 수준을 설정으로 둔 이유는 외부 모델에 보낼 수 있는 범위가
 * 조직마다 다르기 때문이다.
 */
public enum AiMaskLevel {

    /** 원문 그대로. 사내에 설치한 모델처럼 데이터가 밖으로 나가지 않을 때만 쓴다. */
    NONE,

    /**
     * 값은 지우고 진단에 필요한 모양만 남긴다. 문자열은 앞뒤 {@code %} 자리, 숫자는 자릿수,
     * 날짜는 지금으로부터의 거리만 보인다({@code '%@gmail.com'} → {@code '%?'},
     * {@code 1012345678} → {@code ?(10자리)}, {@code '2000-01-01'} → {@code '?(약 26년 전)'}).
     */
    STRUCTURE,

    /** 모든 값을 {@code ?}로. 형변환·와일드카드·선택도처럼 값에 기대는 진단은 약해진다. */
    FULL
}
