package io.dbtower.alert;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인스턴스별 알림 일시 중지(레퍼런스의 "알람 스킵" 대응) — Discord 웹훅 메시지에는 버튼을 달 수
 * 없어(봇 소유 메시지 전용) 알림에 음소거 이모지 반응을 달면 봇이 여기로 중지를 건다.
 *
 * 강제 지점은 WebhookNotifier 하나 — 감지기 셋(회귀·이상·운영)이 전부 그 관문으로 발사하므로
 * 감지기마다 뿌리지 않는다(LBAC의 RegistryService 단일 경계와 같은 논리). 인메모리인 이유:
 * 일시 중지는 수명이 짧은 운영 조작이라 재시작에 날아가도 안전한 방향(알림이 다시 온다)이다.
 */
@Component
public class AlertMuter {

    private final Map<Long, Instant> muteUntil = new ConcurrentHashMap<>();

    public void mute(Long instanceId, Duration duration) {
        muteUntil.put(instanceId, Instant.now().plus(duration));
    }

    public boolean isMuted(Long instanceId) {
        if (instanceId == null) {
            return false;
        }
        Instant until = muteUntil.get(instanceId);
        if (until == null) {
            return false;
        }
        if (Instant.now().isAfter(until)) {
            muteUntil.remove(instanceId); // 만료 정리 — 지연 삭제로 충분(조회 시점 정합만 필요)
            return false;
        }
        return true;
    }

    /** 남은 중지 시간(분) — 만료·미설정이면 0. 봇 확인 답글용. */
    public long remainingMinutes(Long instanceId) {
        Instant until = muteUntil.get(instanceId);
        if (until == null || Instant.now().isAfter(until)) {
            return 0;
        }
        return Duration.between(Instant.now(), until).toMinutes();
    }
}
