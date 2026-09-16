package io.dbtower.alert;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 경보가 실제로 나갔다는 사실 — 쿨다운·음소거·레이트리밋을 통과해 전송에 성공한 감지만 발행한다.
 *
 * <p>다른 모듈(AI 운영 작업)이 경보를 받아 후속 분석을 시작할 수 있게 둔다. 판정 전 후보를 흘리면 쿨다운으로 걸러진
 * 신호마다 후속 작업이 생겨 경보보다 분석이 더 시끄러워진다. 경보 모듈은 누가 듣는지 모른다 — 의존은 듣는 쪽에서 이쪽으로만 난다.</p>
 *
 * @param instanceId    원시 long이 아니라 Long이다 — 저장 전 인스턴스로 감지기를 돌리는 테스트에서 언박싱 NPE로 경보 루프가 멈췄다
 * @param windowMinutes 감지가 본 구간 길이(회귀는 최근+직전 구간, 운영 경보는 현재 상태라 기본 구간)
 */
public record AlertRaisedEvent(Source source, Long instanceId, String instanceName, List<String> findings,
                               int windowMinutes, LocalDateTime raisedAt) {

    public enum Source { REGRESSION, OPERATIONS }

    public AlertRaisedEvent {
        findings = List.copyOf(findings);
    }
}
