package io.dbtower.aiops.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 소견의 수치·인용 대조. 의미가 아니라 글자 대조라는 한계까지 테스트 이름으로 남긴다. */
class ClaimVerifierTest {

    private static final List<String> FACTS = List.of(
            "[orders-db] 헬스 스코어 55점, 등급 F",
            "[orders-db] 쿼리 q1: 평균 12.0ms -> 48.5ms, 초당 3.2 -> 3.1, 호출당 행 1,200.0 -> 98000.0 | SELECT ...");
    private static final List<String> RULES = List.of("[orders-db] 쿼리 q1 평균 지연 304.2% 증가(레이턴시 회귀 임계 200.0%)");

    @Test
    void 사실에_있는_수치와_인용은_통과한다() {
        List<String> problems = ClaimVerifier.verify(
                "q1의 평균 지연이 12ms에서 48.5ms로 늘었고 호출당 행이 1200에서 98,000으로 커졌다.",
                List.of("F2 평균 48.5ms", "G1 304.2% 증가"), List.of("1차로 실행계획을 확인한다"),
                FACTS, RULES, List.of(), "느려진 쿼리 봐줘", List.of());
        assertThat(problems).isEmpty();
    }

    @Test
    void 지어낸_수치와_계산한_배수는_검증_안_됨으로_남는다() {
        List<String> problems = ClaimVerifier.verify("지연이 4배 늘었고 커넥션이 350개로 찼다.",
                List.of("F2"), List.of(), FACTS, RULES, List.of(), "", List.of());
        assertThat(problems).contains("사실 목록에 없는 수치: 4", "사실 목록에 없는 수치: 350");
    }

    @Test
    void 없는_인용_번호와_인용_없는_근거를_잡는다() {
        List<String> problems = ClaimVerifier.verify("소견", List.of("F9 없는 사실", "번호 없는 근거"), List.of(),
                FACTS, RULES, List.of(), "", List.of());
        assertThat(problems).contains("존재하지 않는 인용: F9");
        assertThat(problems).anyMatch(p -> p.startsWith("인용 번호가 없는 근거"));
    }

    @Test
    void 요청_문장의_수치와_사실에_나온_식별자_속_숫자는_주장이_아니다() {
        // 사용자가 "최근 30분"이라고 물었으면 소견의 30은 지어낸 값이 아니다. 사실에 나온 쿼리 다이제스트의 숫자도 수치가 아니다
        List<String> facts = List.of("[pg16-main] 쿼리 3fa2b9: 평균 12.0ms -> 48.5ms");
        List<String> problems = ClaimVerifier.verify("최근 30분 동안 pg16-main의 3fa2b9 쿼리가 48.5ms까지 느려졌다.",
                List.of("F1"), List.of(), facts, List.of(), List.of(), "최근 30분 느려진 쿼리", List.of());
        assertThat(problems).isEmpty();
    }

    @Test
    void 단위가_붙은_지어낸_수치도_잡는다() {
        List<String> problems = ClaimVerifier.verify("평균이 350ms이고 CPU가 92%다.", List.of("F1"), List.of(),
                FACTS, RULES, List.of(), "", List.of());
        assertThat(problems).contains("사실 목록에 없는 수치: 350", "사실 목록에 없는 수치: 92");
    }

    @Test
    void 분석_구간_시각_인용은_숫자로_쪼개지_않고_구간과_대조한다() {
        // 169절 실측 문장 그대로 — 뒤쪽 시각의 날짜를 생략했다
        String action = "같은 분석 구간(2026-09-15T16:49:11.082867Z ~ 17:49:11.082867Z)으로 query-stats를 조회";
        List<String> window = List.of("분석 구간 2026-09-15T16:49:11.082867Z ~ 2026-09-15T17:49:11.082867Z");
        assertThat(ClaimVerifier.verify("소견", List.of("F1"), List.of(action), FACTS, RULES, List.of(), "", window))
                .isEmpty();
        // 구간 밖 시각이나 시간대를 바꿔 적은 시각은 대조되지 않는다
        assertThat(ClaimVerifier.verify("01:49:11부터 느려졌다", List.of("F1"), List.of(), FACTS, RULES, List.of(), "",
                window)).containsExactly("사실 목록에 없는 시각: 01:49:11");
    }

    @Test
    void 표기_차이는_같은_수로_본다() {
        assertThat(ClaimVerifier.numbersIn("1,200.50ms")).containsExactly("1200.5");
        assertThat(ClaimVerifier.numbersIn("48.0")).containsExactly("48");
    }
}
