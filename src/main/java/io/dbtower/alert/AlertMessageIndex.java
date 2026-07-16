package io.dbtower.alert;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Discord 알림 메시지 id ↔ 대상 인스턴스 id 매핑 (Gateway 봇의 이모지 트리거용).
 *
 * 봇이 반응이 달린 알림의 대상 인스턴스를 알아내는 방법은 둘이다: (1) 메시지 embed 내용을 읽어
 * 제목에서 인스턴스명을 파싱 — 이건 <b>Message Content 특권 인텐트</b>가 있어야 웹훅이 쓴 메시지의
 * embed를 REST로 읽을 수 있다. (2) <b>발사 시점에 메시지 id를 인스턴스에 매핑</b>해두고 반응 때
 * 조회 — 특권 인텐트가 필요 없다. 여기는 (2)를 위한 인덱스다(특권 인텐트 회피 = 더 적은 권한).
 *
 * 인메모리 유한 캐시(LRU) — 재시작하면 비지만, 알림→반응 왕복은 짧아 실무 영향이 작다(반응이
 * 오래 뒤에 달려 인덱스에서 밀려났으면 봇이 embed 파싱으로 폴백한다). 발사(웹훅 스레드)와 조회
 * (Gateway 스레드)가 다른 스레드라 접근을 배타화한다.
 */
@Component
public class AlertMessageIndex {

    /** 최근 알림 매핑 상한 — 초과분은 오래된 것부터 밀어낸다(반응은 보통 알림 직후에 달린다). */
    private static final int MAX = 500;

    private final Map<String, Long> messageToInstance = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX;
        }
    };

    public synchronized void record(String messageId, Long instanceId) {
        if (messageId != null && instanceId != null) {
            messageToInstance.put(messageId, instanceId);
        }
    }

    public synchronized Long instanceFor(String messageId) {
        return messageToInstance.get(messageId);
    }
}
