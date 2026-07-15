package io.dbtower.finops.internal;

import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.IndexUsage;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 미사용 인덱스 후보 (D6) — operator.indexUsage()의 실제 사용 통계로 판정한다. D6의 핵심 신호이자,
 * 구조만 보는 D2 중복·잉여 판정과 상호보완이다(D2는 "겹치는 인덱스", 여기는 "안 쓰인 인덱스").
 *
 * scanCount=0(통계 리셋 이후 누적 사용 0회)이고 유니크/PK 뒷받침이 아닌 인덱스만 후보로 올린다 —
 * 유니크·PK 인덱스는 안 쓰여도 제약 유지에 필요할 수 있어 제외한다(오탐 방지). 삭제 지시가 아니라
 * "검토 후보"이며, 서버 가동 기간을 함께 봐야 함을 근거·권고에 명시한다(방금 재기동한 서버의 0회는 미사용이 아님).
 *
 * 기종별 정직: PostgreSQL·MySQL·SQL Server·MongoDB는 사용 카운터로 NATIVE 판정. Oracle은 인덱스 사용
 * 추적이 기본·권한 보장이 아니라 operator가 UNSUPPORTED를 내므로, 여기서는 supports=false로 두어
 * FinOpsService가 UNSUPPORTED로 정직하게 표기하게 한다("미사용 인덱스 없음"으로 위장하지 않는다).
 */
@Component
public class UnusedIndexAnalyzer implements FinOpsAnalyzer {

    /** 인덱스 사용 통계 조회 상한(대량 스키마 방어) */
    private static final int SCAN_LIMIT = 300;

    @Override
    public String id() {
        return "unused-index";
    }

    @Override
    public String title() {
        return "미사용 인덱스 후보";
    }

    @Override
    public boolean supports(DbmsType type) {
        // Oracle만 인덱스 사용 통계 미지원 → UNSUPPORTED로 넘긴다(operator.indexUsage도 UNSUPPORTED 안내 행)
        return type != DbmsType.ORACLE;
    }

    @Override
    public List<WasteCandidate> analyze(DatabaseInstance instance, DbmsOperator operator) {
        List<WasteCandidate> candidates = new ArrayList<>();
        for (IndexUsage u : operator.indexUsage(SCAN_LIMIT)) {
            // 방어: 지원 기종인데도 통계 미가용(예: MySQL performance_schema off)으로 UNSUPPORTED 행이 오면 건너뛴다
            if (!IndexUsage.NATIVE.equals(u.source())) {
                continue;
            }
            // 유니크/PK 인덱스는 미사용이라도 제약 유지에 필요할 수 있어 후보에서 제외
            if (u.unique() || u.scanCount() == null || u.scanCount() != 0) {
                continue;
            }
            String size = u.sizeBytes() == null ? "" : " · 크기 " + FinOpsFormat.bytes(u.sizeBytes());
            candidates.add(new WasteCandidate(
                    WasteKind.UNUSED_INDEX, Severity.WARNING,
                    u.tableName() + "." + u.indexName(),
                    "사용 통계상 스캔 0회(통계 리셋 이후 누적)" + size
                            + ". 미사용 인덱스는 쓰기마다 갱신 비용을 물고 저장공간을 잡아먹는다.",
                    "서버 가동 기간이 충분한지 확인하고(방금 재기동한 서버의 0회는 미사용이 아님), "
                            + "유니크·FK 지원 인덱스가 아니면 삭제 후보로 검토한다. 실행은 사람이 한다."));
        }
        return candidates;
    }
}
