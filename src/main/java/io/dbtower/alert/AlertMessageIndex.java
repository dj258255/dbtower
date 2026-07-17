package io.dbtower.alert;

import io.dbtower.alert.internal.domain.AlertMessageMapping;
import io.dbtower.alert.internal.persistence.AlertMessageMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Discord 알림 메시지 id ↔ 대상 인스턴스 id 매핑 (Gateway 봇의 이모지 트리거용).
 *
 * 봇이 반응이 달린 알림의 대상 인스턴스를 알아내는 방법은 둘이다: (1) 메시지 embed 내용을 읽어
 * 제목에서 인스턴스명을 파싱 — 이건 <b>Message Content 특권 인텐트</b>가 있어야 웹훅이 쓴 메시지의
 * embed를 REST로 읽을 수 있다. (2) <b>발사 시점에 메시지 id를 인스턴스에 매핑</b>해두고 반응 때
 * 조회 — 특권 인텐트가 필요 없다. 여기는 (2)를 위한 인덱스다(특권 인텐트 회피 = 더 적은 권한).
 *
 * 메타 DB 영속(V21) — 인메모리였을 때는 재시작하면 비어서, 재시작 전에 온 알림에는 반응해도
 * "대상을 알 수 없다"가 됐다(실측). 메타 DB에 두면 재시작·다중 노드에서도 옛 알림 반응이
 * 동작한다(공유 세션·로그인 잠금 카운터를 메타 DB로 옮긴 것과 같은 논리).
 *
 * 기록 실패는 알림 발송을 막지 않는다 — 매핑은 부가 기능(반응 진단)이고 알림 도착이 본질이라,
 * DB 오류를 삼키고 로그만 남긴다(반응 시 embed 파싱 폴백이 한 번 더 받친다).
 */
@Component
public class AlertMessageIndex {

    private static final Logger log = LoggerFactory.getLogger(AlertMessageIndex.class);

    /** 보존 일수 — 이보다 오래된 알림에 반응이 달리는 일은 사실상 없다. */
    private static final int RETENTION_DAYS = 30;
    /** 보존 정리를 record N회마다 한 번만 — 알림 볼륨이 낮아 이 정도로 충분하다. */
    private static final int PRUNE_EVERY = 50;

    private final AlertMessageMappingRepository repository;
    private final AtomicInteger recordCount = new AtomicInteger();

    public AlertMessageIndex(AlertMessageMappingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String messageId, Long instanceId) {
        if (messageId == null || instanceId == null) {
            return;
        }
        try {
            repository.save(new AlertMessageMapping(messageId, instanceId, LocalDateTime.now()));
            if (recordCount.incrementAndGet() % PRUNE_EVERY == 0) {
                repository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
            }
        } catch (RuntimeException e) {
            log.warn("알림 메시지 매핑 기록 실패 message={} — 알림 발송은 계속한다: {}", messageId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Long instanceFor(String messageId) {
        if (messageId == null) {
            return null;
        }
        try {
            return repository.findById(messageId).map(AlertMessageMapping::getInstanceId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("알림 메시지 매핑 조회 실패 message={}: {}", messageId, e.getMessage());
            return null;
        }
    }

    /** 이 알림의 반응 진단이 이미 수행됐는가 — 재시작 후 보충 스캔의 중복 진단 방지(처리 이력 영속). */
    @Transactional(readOnly = true)
    public boolean alreadyProcessed(String messageId) {
        if (messageId == null) {
            return false;
        }
        try {
            return repository.findById(messageId)
                    .map(m -> m.getProcessedAt() != null).orElse(false);
        } catch (RuntimeException e) {
            log.warn("알림 메시지 처리 이력 조회 실패 message={}: {}", messageId, e.getMessage());
            return false;
        }
    }

    @Transactional
    public void markProcessed(String messageId) {
        if (messageId == null) {
            return;
        }
        try {
            repository.findById(messageId).ifPresent(m -> {
                m.markProcessed(LocalDateTime.now());
                repository.save(m);
            });
        } catch (RuntimeException e) {
            log.warn("알림 메시지 처리 이력 기록 실패 message={}: {}", messageId, e.getMessage());
        }
    }
}
