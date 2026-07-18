package io.dbtower.alert.internal;

import io.dbtower.alert.internal.persistence.ConfigDriftDao;
import io.dbtower.alert.internal.persistence.ConfigDriftDao.ParamChangeRow;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 설정 드리프트 판정 (운영 병목 아크 B1). 한 인스턴스의 현재 파라미터를 거울(직전 확정 상태)과
 * 비교해 변경분을 저장하고, 첫 수집이 아닌 실제 변경이면 경보 대상으로 표시한다.
 *
 * 원천은 operator.parameters() 재사용(신규 Operator 코드 0줄). 민감값은 그 단계에서 이미
 * 마스킹돼 좌우 동일 토큰이라 '변경'으로 튀지 않는다. 노이즈 파라미터(세션 로컬·런타임 파생)는
 * 이름 기반으로 걸러 시작한다 — 노이즈가 기능을 죽이지 않게(함정 1).
 */
@Service
public class ConfigDriftService {

    /**
     * 드리프트에서 제외할 파라미터 — 값이 세션·런타임에 따라 정상적으로 흔들려 '변경'이 아닌 것들.
     * 이름 소문자 부분일치. 보수적으로 시작하고, 오탐이 관측되면 넓힌다.
     */
    private static final Set<String> NOISE = Set.of(
            "application_name", "search_path", "session_authorization",
            "client_encoding", "timezone", "time_zone", "lc_", "transaction_",
            "pg_stat_statements.", "default_transaction_",
            // 런타임 파생·상태성 값(설정이 아니라 현재 상태)
            "server_encoding", "in_hot_standby", "data_directory_mode", "config_file",
            "hba_file", "ident_file", "data_directory");

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ConfigDriftDao dao;

    public ConfigDriftService(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                              ConfigDriftDao dao) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.dao = dao;
    }

    /** 한 수집의 결과 — 경보를 낼지(baseline·무변경이면 false)와 변경 목록. */
    public record DriftResult(boolean changed, boolean baseline, List<ParamChangeRow> changes) {
        static DriftResult noChange() {
            return new DriftResult(false, false, List.of());
        }
        static DriftResult asBaseline() {
            return new DriftResult(false, true, List.of());
        }
    }

    /**
     * 한 인스턴스 수집 — 파라미터를 읽어 거울과 diff, 저장까지. 첫 수집은 기준선(경보 없음),
     * 무변경은 스냅샷 1행만, 변경은 로그+거울 갱신 후 changed=true.
     * 대상 조회 실패는 호출자(잡)가 격리한다(여기서 던진다).
     */
    public DriftResult collect(DatabaseInstance instance, LocalDateTime capturedAt) {
        long id = instance.getId();
        Map<String, String> fresh = freshParams(instance);
        String hash = hash(fresh);
        Map<String, String> previous = dao.currentParams(id);

        if (previous.isEmpty()) {
            dao.insertBaseline(id, fresh);
            dao.insertSnapshot(id, capturedAt, hash, 0, true);
            return DriftResult.asBaseline();
        }

        List<ParamChangeRow> changes = diff(previous, fresh);
        long snapshotId = dao.insertSnapshot(id, capturedAt, hash, changes.size(), false);
        if (changes.isEmpty()) {
            return DriftResult.noChange();
        }
        dao.insertChanges(snapshotId, id, capturedAt, changes);
        dao.applyDeltas(id, changes);
        return new DriftResult(true, false, changes);
    }

    /** 콘솔 타임라인(P3) — 최신 변경 이벤트. */
    public List<ParamChangeRow> timeline(long instanceId, int limit) {
        return dao.recentChanges(instanceId, limit);
    }

    /** 플랜 플립 대조(P4) — 기준 시각 ±windowHours 안의 설정 변경 수. */
    public int changesAround(long instanceId, LocalDateTime center, int windowHours) {
        return dao.changeCountAround(instanceId, center, windowHours);
    }

    private Map<String, String> freshParams(DatabaseInstance instance) {
        List<DbParameter> params = operatorFactory.create(instance).parameters();
        Map<String, String> map = new LinkedHashMap<>();
        for (DbParameter p : params) {
            if (p.name() == null || isNoise(p.name())) {
                continue;
            }
            map.put(p.name(), p.value() == null ? "" : p.value());
        }
        return map;
    }

    private static boolean isNoise(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return NOISE.stream().anyMatch(lower::contains);
    }

    /** 거울(old) 대비 fresh(new)의 변경 — 값이 다른 것(CHANGED), 새로 생긴 것(ADDED), 사라진 것(REMOVED). */
    private static List<ParamChangeRow> diff(Map<String, String> old, Map<String, String> fresh) {
        List<ParamChangeRow> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : fresh.entrySet()) {
            String oldVal = old.get(e.getKey());
            if (oldVal == null) {
                changes.add(new ParamChangeRow(e.getKey(), null, e.getValue(), "ADDED"));
            } else if (!oldVal.equals(e.getValue())) {
                changes.add(new ParamChangeRow(e.getKey(), oldVal, e.getValue(), "CHANGED"));
            }
        }
        for (Map.Entry<String, String> e : old.entrySet()) {
            if (!fresh.containsKey(e.getKey())) {
                changes.add(new ParamChangeRow(e.getKey(), e.getValue(), null, "REMOVED"));
            }
        }
        return changes;
    }

    /** 정규화(name=value 정렬) 후 SHA-256 hex — 무변경 증거이자 스냅샷의 지문. */
    private static String hash(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e); // JDK 표준이라 도달 불가
        }
    }
}
