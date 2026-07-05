package io.dbtower.finops;

import java.util.List;

/**
 * 한 인스턴스에 대한 FinOps 분석기 하나의 결과 (D6) — Advisor의 AdvisorCheck와 같은 정직 구도.
 *
 * status로 "낭비 후보 없음/있음/미지원/오류"를 구분한다 — UNSUPPORTED를 "후보 없음"으로 위장하지 않는다.
 * 예: Oracle 미사용 인덱스는 사용 통계를 신뢰성 있게 못 얻어 UNSUPPORTED이지, "미사용 인덱스가 없다"가 아니다.
 * candidates는 CANDIDATES일 때만 채워지고, ERROR면 사유가 note에 담긴다(분석기 하나의 실패를 인스턴스별 격리).
 *
 * @param analyzer   FinOpsAnalyzer.id()
 * @param title      FinOpsAnalyzer.title()
 * @param status     분석 상태
 * @param candidates 낭비 후보(CANDIDATES에서만 비어있지 않음)
 * @param note       UNSUPPORTED 사유·ERROR 메시지(그 외 null)
 */
public record FinOpsCheck(String analyzer, String title, Status status,
                          List<WasteCandidate> candidates, String note) {

    public enum Status { OK, CANDIDATES, UNSUPPORTED, ERROR }

    public static FinOpsCheck ok(FinOpsAnalyzer a) {
        return new FinOpsCheck(a.id(), a.title(), Status.OK, List.of(), null);
    }

    public static FinOpsCheck candidates(FinOpsAnalyzer a, List<WasteCandidate> candidates) {
        return new FinOpsCheck(a.id(), a.title(), Status.CANDIDATES, List.copyOf(candidates), null);
    }

    public static FinOpsCheck unsupported(FinOpsAnalyzer a, String reason) {
        return new FinOpsCheck(a.id(), a.title(), Status.UNSUPPORTED, List.of(), reason);
    }

    public static FinOpsCheck error(FinOpsAnalyzer a, String message) {
        return new FinOpsCheck(a.id(), a.title(), Status.ERROR, List.of(), message);
    }
}
