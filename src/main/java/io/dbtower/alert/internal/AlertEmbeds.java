package io.dbtower.alert.internal;

import io.dbtower.alert.internal.WebhookNotifier.Embed;
import io.dbtower.registry.DatabaseInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * 감지 알림을 리치 embed로 꾸미는 공용 빌더 — 회귀·운영·이상 감지가 "DB팀 문의" 카드와 같은
 * 시각 언어를 쓰게 한다(밋밋한 텍스트 대신 색 막대·구조화 필드). Slack·미설정 경로는 WebhookNotifier가
 * 텍스트 폴백으로 처리하므로, 여기서는 embed 구조만 만든다.
 *
 * 색은 심각도의 결을 따른다 — 운영 경보(빨강), 회귀(앰버), 이상 감지(보라). 콘솔 카드의 sev 색과 맞춘다.
 */
public final class AlertEmbeds {

    public static final int RED = 0xD6404F;    // 운영 경보 — 지금 위험
    public static final int AMBER = 0xF08C2D;  // 회귀 — 성능 신호
    public static final int PURPLE = 0x8B5CF6; // 이상 감지 — 평소와 다름
    public static final int BLUE = 0x3B82F6;   // 설정 드리프트 — 형상 변경

    private AlertEmbeds() {
    }

    /**
     * 설정 드리프트 embed (B1) — 파라미터 변경을 old→new로 구조화한다. "누가"는 대상 DB가 주지
     * 않는 정보라 싣지 않고, 콘솔 링크로 대상 DB 감사 로그의 몫임을 안내한다(정직).
     *
     * @param instance 대상 인스턴스
     * @param changes  "name: old → new" 형태로 이미 렌더된 변경 목록(호출자가 마스킹·표기 결정)
     * @param deeplink 콘솔 설정 이력 딥링크(null이면 생략)
     */
    public static Embed forConfigDrift(DatabaseInstance instance, List<String> changes, String deeplink) {
        List<Embed.Field> fields = new ArrayList<>();
        fields.add(new Embed.Field("인스턴스", instance.getName() + " (" + instance.getType() + ")", true));
        fields.add(new Embed.Field("변경 수", String.valueOf(changes.size()), true));
        if (instance.getTeamLabel() != null && !instance.getTeamLabel().isBlank()) {
            fields.add(new Embed.Field("담당", instance.getTeamLabel(), true));
        }
        StringBuilder body = new StringBuilder();
        for (String c : changes) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("• ").append(c);
        }
        fields.add(new Embed.Field("설정 변경", body.toString(), false));
        // "누가"는 대상 DB가 주지 않는다 — 콘솔/대상 감사 로그로 안내(값 지어내기 금지)
        fields.add(new Embed.Field("참고", "변경 주체는 대상 DB의 감사 로그에서 확인하세요.", false));
        if (instance.getConsoleUrl() != null && !instance.getConsoleUrl().isBlank()) {
            fields.add(new Embed.Field("콘솔", link("콘솔 열기", instance.getConsoleUrl()), false));
        }
        if (deeplink != null && !deeplink.isBlank()) {
            fields.add(new Embed.Field("설정 이력", link("변경 이력 보기", deeplink), false));
        }
        return new Embed("DBTower 설정 변경 감지 — " + instance.getName(), BLUE, fields);
    }

    /**
     * 표준 감지 embed — 인스턴스·(선택)맥락 필드 + 지적 목록 + (선택)AI 분석·진단 링크.
     *
     * @param kind        "회귀 감지" 등 — 제목에 붙는다
     * @param color       심각도 색
     * @param instance    대상 인스턴스(이름·기종·담당 팀·콘솔 링크)
     * @param contextName 맥락 필드 이름(예: "구간") — null이면 생략
     * @param contextValue 맥락 필드 값
     * @param findings    지적 목록(불릿으로 묶어 한 필드)
     * @param analysis    AI 1차 분석(null이면 생략)
     * @param deeplink    진단 딥링크(null이면 생략)
     */
    public static Embed forDetection(String kind, int color, DatabaseInstance instance,
                                     String contextName, String contextValue,
                                     List<String> findings, String analysis, String deeplink) {
        List<Embed.Field> fields = new ArrayList<>();
        fields.add(new Embed.Field("인스턴스", instance.getName() + " (" + instance.getType() + ")", true));
        if (contextName != null && contextValue != null && !contextValue.isBlank()) {
            fields.add(new Embed.Field(contextName, contextValue, true));
        }
        if (instance.getTeamLabel() != null && !instance.getTeamLabel().isBlank()) {
            fields.add(new Embed.Field("담당", instance.getTeamLabel(), true));
        }
        // 지적은 불릿 한 덩어리 — 항목 사이에 빈 줄(\n\n)을 넣어 다닥다닥 붙지 않게 한다(가독성).
        // Discord 필드 값 한도(1024)는 WebhookNotifier가 경계에서 자른다.
        StringBuilder bullets = new StringBuilder();
        for (String f : findings) {
            if (bullets.length() > 0) {
                bullets.append("\n\n");
            }
            bullets.append("• ").append(f);
        }
        fields.add(new Embed.Field("감지 내용", bullets.toString(), false));
        if (analysis != null && !analysis.isBlank()) {
            fields.add(new Embed.Field("AI 1차 분석", analysis, false));
        }
        // 링크는 Discord 마스킹 마크다운 [텍스트](url)로 — 날것의 URL 인코딩 문자열이 필드를 도배하지
        // 않게 클릭 가능한 짧은 라벨로 렌더한다. Slack·미설정 폴백은 텍스트라 원본 URL이 그대로 나간다.
        if (instance.getConsoleUrl() != null && !instance.getConsoleUrl().isBlank()) {
            fields.add(new Embed.Field("콘솔", link("콘솔 열기", instance.getConsoleUrl()), false));
        }
        if (deeplink != null && !deeplink.isBlank()) {
            fields.add(new Embed.Field("진단", link("콘솔에서 진단하기", deeplink), false));
        }
        return new Embed("DBTower " + kind + " — " + instance.getName(), color, fields);
    }

    /**
     * Discord 마스킹 링크. 라벨의 대괄호와 URL의 괄호는 마크다운을 깨므로 이스케이프한다
     * (URL의 ()는 %28/%29로 — 링크 파서가 닫는 괄호에서 잘리는 것 방지).
     */
    static String link(String label, String url) {
        String safeLabel = label.replace("[", "\\[").replace("]", "\\]");
        String safeUrl = url.replace("(", "%28").replace(")", "%29");
        return "[" + safeLabel + "](" + safeUrl + ")";
    }
}
