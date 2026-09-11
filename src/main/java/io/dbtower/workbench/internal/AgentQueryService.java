package io.dbtower.workbench.internal;

import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.internal.WorkbenchService.QueryView;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.persistence.WorkbenchSettingRepository;
import io.dbtower.workbench.internal.domain.WorkbenchSetting;
import org.springframework.stereotype.Service;

/**
 * 외부 AI 에이전트(MCP)의 조회 — 사람의 조회 경로를 그대로 타되, 결과 값이 에이전트로 나가도 되는 인스턴스에서만 연다.
 *
 * <p>워크벤치 AI 보조와 같은 정책(인스턴스별 "결과 값 AI 공유", 기본 꺼짐)을 쓴다. 채널이 달라도 "대상 DB의 값이 모델로 나간다"는
 * 위험은 같아서다. 꺼져 있으면 대상 DB에 닿기 전에 403, 켜져 있어도 조회 계정·읽기 전용·마스킹·기록을 전부 거치고 행 상한을 더 낮춘다.
 * MCP 호출은 서비스 토큰으로 오기 때문에 기록의 주체는 사람 대신 토큰이 되고, 그래서 action을 따로 남겨 사람의 조회와 구분한다.
 */
@Service
public class AgentQueryService {

    static final int AGENT_ROW_LIMIT = 50;
    static final String ACTION = "AGENT_QUERY";

    private final RegistryService registry;
    private final WorkbenchSettingRepository settings;
    private final WorkbenchService workbench;

    public AgentQueryService(RegistryService registry, WorkbenchSettingRepository settings, WorkbenchService workbench) {
        this.registry = registry;
        this.settings = settings;
        this.workbench = workbench;
    }

    public QueryView query(Long instanceId, String statement, Integer rowLimit) {
        registry.findById(instanceId);
        boolean allowed = settings.findById(instanceId).map(WorkbenchSetting::isAllowAiResultValues).orElse(false);
        if (!allowed) {
            throw new WorkbenchRejection(403, "이 인스턴스는 조회 결과 값을 AI로 보내지 않는다(ADMIN이 워크벤치 설정에서 켠 인스턴스만 연다)", null);
        }
        int limit = rowLimit == null ? AGENT_ROW_LIMIT : Math.clamp(rowLimit, 1, AGENT_ROW_LIMIT);
        return workbench.runAs(instanceId, statement, limit, ACTION);
    }
}
