package io.dbtower.insight;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.registry.RegistryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스키마 조회·비교 API (B7). 둘 다 진단(읽기)이라 인증 사용자(VIEWER부터) 접근 —
 * SecurityConfig의 anyRequest().authenticated()로 커버되고, 대상 DB를 바꾸지 않으므로 ADMIN 경계가 아니다.
 */
@RestController
public class SchemaController {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final SchemaDiffService diffService;

    public SchemaController(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                            SchemaDiffService diffService) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.diffService = diffService;
    }

    /** 단건 스키마 스냅샷 — 한 인스턴스의 테이블·컬럼·인덱스 구조 요약 */
    @GetMapping("/api/instances/{id}/schema")
    public SchemaSnapshot schema(@PathVariable Long id) {
        return operatorFactory.create(registryService.findById(id)).describeSchema();
    }

    /**
     * 두 인스턴스 스키마 diff — GET /api/schema-diff?left={id}&right={id}.
     * 기종이 달라도 비교는 하되(다른 기종 두 대의 논리 구조가 같은지도 궁금할 수 있으므로),
     * 타입 표기 차이를 warning으로 실어 오독을 막는다(같은 기종만 허용해 막기보다 정직하게 안내).
     */
    @GetMapping("/api/schema-diff")
    public SchemaDiffService.SchemaDiff schemaDiff(@RequestParam Long left, @RequestParam Long right) {
        SchemaSnapshot leftSnapshot = operatorFactory.create(registryService.findById(left)).describeSchema();
        SchemaSnapshot rightSnapshot = operatorFactory.create(registryService.findById(right)).describeSchema();
        return diffService.diff(leftSnapshot, rightSnapshot);
    }
}
