package io.dbtower.alert;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DB팀 문의가 접수됐다는 사실 — 웹훅으로 나간 뒤 발행한다.
 *
 * <p>alert는 누가 듣는지 모른다. AI 운영 작업이 이 이벤트를 받아 사실·규칙·소견을 뒤에 붙이지만,
 * 그 실패가 문의를 되돌리지는 않는다. SQL은 마스킹된 본문이다 — 문의 창의 SQL은 사람이 직접 친 원문이라
 * 실값이 실려 오는 대표 경로이고, 이 이벤트는 모듈 밖으로 나간다.</p>
 *
 * <p>마스킹 범위: SQL만 리터럴을 가린다(사람이 직접 친 원문이라 실값이 실려 오는 대표 경로다).
 * findings·note는 사람이 DB팀에게 쓴 문장 그대로 둔다 — 같은 문장이 이미 웹훅 채널로 나가고, 가리면
 * 문의의 뜻이 사라진다. 대신 이 이벤트가 모델 호출까지 간다는 것을 알고 쓰는 값이다.</p>
 */
public record InquiryRaisedEvent(String inquiryId, Long instanceId, String instanceName, String requester,
                                 String maskedSql, List<String> findings, String note, LocalDateTime raisedAt) {

    public InquiryRaisedEvent {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
