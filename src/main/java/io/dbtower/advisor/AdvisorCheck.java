package io.dbtower.advisor;

import java.util.List;

/**
 * 한 인스턴스에 대한 Advisor 하나의 점검 결과.
 *
 * status로 "통과/위반/미지원/오류"를 정직하게 구분한다 — UNSUPPORTED를 OK로 위장하지 않는다.
 * findings는 VIOLATIONS일 때만 채워지고, ERROR면 사유가 note에 담긴다(스윕이 죽지 않게 인스턴스별 격리).
 *
 * @param advisor  Advisor.id()
 * @param title    Advisor.title()
 * @param status   점검 상태
 * @param findings 위반 목록(VIOLATIONS에서만 비어있지 않음)
 * @param note     UNSUPPORTED 사유·ERROR 메시지(그 외 null)
 */
public record AdvisorCheck(String advisor, String title, Status status,
                           List<AdvisorFinding> findings, String note) {

    public enum Status { OK, VIOLATIONS, UNSUPPORTED, ERROR, SHARED }

    public static AdvisorCheck ok(Advisor a) {
        return new AdvisorCheck(a.id(), a.title(), Status.OK, List.of(), null);
    }

    public static AdvisorCheck violations(Advisor a, List<AdvisorFinding> findings) {
        return new AdvisorCheck(a.id(), a.title(), Status.VIOLATIONS, List.copyOf(findings), null);
    }

    public static AdvisorCheck unsupported(Advisor a) {
        return new AdvisorCheck(a.id(), a.title(), Status.UNSUPPORTED, List.of(),
                "이 기종에는 적용되지 않는 점검이다");
    }

    public static AdvisorCheck error(Advisor a, String message) {
        return new AdvisorCheck(a.id(), a.title(), Status.ERROR, List.of(), message);
    }

    /** 서버 공유 dedup (Phase 4) — 호스트 스코프 점검을 같은 호스트의 다른 인스턴스가 이미 수행했다. */
    public static AdvisorCheck shared(Advisor a, String coveredBy) {
        return new AdvisorCheck(a.id(), a.title(), Status.SHARED, List.of(),
                "같은 호스트라 " + coveredBy + " 점검으로 판정을 공유한다(서버 공유 — 중복 탐침 방지)");
    }
}
