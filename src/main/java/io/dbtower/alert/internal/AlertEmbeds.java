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

    private AlertEmbeds() {
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
        // 지적은 불릿 한 덩어리 — Discord 필드 값 한도(1024)는 WebhookNotifier가 경계에서 자른다
        StringBuilder bullets = new StringBuilder();
        for (String f : findings) {
            bullets.append("• ").append(f).append('\n');
        }
        fields.add(new Embed.Field("감지 내용", bullets.toString().strip(), false));
        if (analysis != null && !analysis.isBlank()) {
            fields.add(new Embed.Field("AI 1차 분석", analysis, false));
        }
        if (instance.getConsoleUrl() != null && !instance.getConsoleUrl().isBlank()) {
            fields.add(new Embed.Field("콘솔", instance.getConsoleUrl(), false));
        }
        if (deeplink != null && !deeplink.isBlank()) {
            fields.add(new Embed.Field("진단 열기", deeplink, false));
        }
        return new Embed("DBTower " + kind + " — " + instance.getName(), color, fields);
    }
}
