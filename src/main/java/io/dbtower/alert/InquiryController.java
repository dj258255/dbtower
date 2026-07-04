package io.dbtower.alert;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB팀 문의 엔드포인트 (Phase B8) — 당근 KDMS 3단계("DB팀 문의")의 마지막 조각.
 *
 * 컨트롤러를 insight가 아니라 alert 모듈에 둔 이유는 InquiryService 주석 참고(순환 회피).
 * 인가: SecurityConfig의 anyRequest().authenticated()에 걸린다 — 문의는 협업 기능이라
 * VIEWER도 보낼 수 있게 ADMIN 경계에 넣지 않았다(등록/삭제/백업과 달리 대상 DB를 바꾸지 않는다).
 * 감사: POST라 AuditInterceptor가 자동 기록한다(A6).
 */
@RestController
public class InquiryController {

    private final InquiryService inquiryService;

    public InquiryController(InquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @PostMapping("/api/instances/{id}/inquiry")
    public InquiryService.InquiryResult inquiry(@PathVariable Long id,
                                                @RequestBody InquiryService.InquiryRequest req) {
        return inquiryService.submit(id, req);
    }
}
