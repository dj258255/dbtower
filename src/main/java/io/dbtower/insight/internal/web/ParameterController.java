package io.dbtower.insight.internal.web;

import io.dbtower.insight.internal.ParameterDiffService;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 파라미터 조회·비교 API (B6). 읽기 진단이라 스키마 diff와 같은 갈래지만, 인가 경계는 다르다.
 *
 * 인가 결정: 두 엔드포인트 모두 ADMIN으로 제한한다(SecurityConfig). 근거 —
 * (1) 파라미터 값에는 인프라 형상(메모리 사이징·파일 경로·복제/네트워크 설정)과 자격증명이 섞인다.
 * (2) 민감값 마스킹은 이름 기반 휴리스틱이라 5기종 전부를 완전히 가린다고 보장할 수 없다.
 * 그래서 "전체 조회"뿐 아니라 값을 그대로 노출하는 "diff"도 A6 감사·보안 조회와 같은 ADMIN 경계에 둔다.
 * (스키마 diff는 구조만 드러내 인증 사용자에게 열지만, 파라미터는 값 자체가 민감해 한 단계 위로 올린다.)
 */
@RestController
public class ParameterController {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ParameterDiffService diffService;

    public ParameterController(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                               ParameterDiffService diffService) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.diffService = diffService;
    }

    /** 단건 파라미터 전량 — 한 인스턴스의 설정값 목록(민감값은 마스킹됨) */
    @GetMapping("/api/instances/{id}/parameters")
    public List<DbParameter> parameters(@PathVariable Long id) {
        return operatorFactory.create(registryService.findById(id)).parameters();
    }

    /**
     * 두 인스턴스 파라미터 drift — GET /api/param-diff?left={id}&right={id}.
     * 차이(값이 다른 항목 + 한쪽에만 있는 항목)만 돌려준다. 기종이 다르면 경고를 함께 싣는다.
     */
    @GetMapping("/api/param-diff")
    public ParameterDiffService.ParameterDiff paramDiff(@RequestParam Long left, @RequestParam Long right) {
        DatabaseInstance leftInstance = registryService.findById(left);
        DatabaseInstance rightInstance = registryService.findById(right);
        List<DbParameter> leftParams = operatorFactory.create(leftInstance).parameters();
        List<DbParameter> rightParams = operatorFactory.create(rightInstance).parameters();
        return diffService.diff(leftInstance.getType().name(), leftParams,
                rightInstance.getType().name(), rightParams);
    }
}
