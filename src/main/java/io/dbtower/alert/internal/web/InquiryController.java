package io.dbtower.alert.internal.web;

import io.dbtower.alert.internal.InquiryService;
import io.dbtower.alert.internal.ReferencedSchemaService;
import io.dbtower.operator.model.TableDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB팀 문의 엔드포인트 (Phase B8) — 식별→분석→문의 3단계의 마지막 조각.
 *
 * 컨트롤러를 insight가 아니라 alert 모듈에 둔 이유는 InquiryService 주석 참고(순환 회피).
 * 인가: SecurityConfig의 anyRequest().authenticated()에 걸린다 — 문의는 협업 기능이라
 * VIEWER도 보낼 수 있게 ADMIN 경계에 넣지 않았다(등록/삭제/백업과 달리 대상 DB를 바꾸지 않는다).
 * 감사: POST라 AuditInterceptor가 자동 기록한다(A6).
 */
@RestController
public class InquiryController {

    private final InquiryService inquiryService;
    private final ReferencedSchemaService referencedSchema;

    public InquiryController(InquiryService inquiryService, ReferencedSchemaService referencedSchema) {
        this.inquiryService = inquiryService;
        this.referencedSchema = referencedSchema;
    }

    @PostMapping("/api/instances/{id}/inquiry")
    public InquiryService.InquiryResult inquiry(@PathVariable Long id,
                                                @RequestBody InquiryService.InquiryRequest req) {
        return inquiryService.submit(id, req);
    }

    /** 상세 패널이 문의 전에 미리 보여줄 "관련 테이블 구조" — 쿼리를 주면 참조 테이블의 컬럼·인덱스·행수 반환 */
    @PostMapping("/api/instances/{id}/referenced-schema")
    public ReferencedSchemaService.ReferencedSchema referencedSchema(@PathVariable Long id,
                                                                     @RequestBody SqlRequest req) {
        return referencedSchema.describe(id, req.sql());
    }

    /** 테이블 하나의 상세 — 테이블 상세 정보(CREATE TABLE·기본 통계·인덱스 카디널리티, 심화 아크 3) */
    @PostMapping("/api/instances/{id}/table-detail")
    public TableDetail tableDetail(@PathVariable Long id,
                                   @RequestBody TableRequest req) {
        return referencedSchema.tableDetail(id, req.table());
    }

    public record SqlRequest(String sql) {
    }

    public record TableRequest(String table) {
    }
}
