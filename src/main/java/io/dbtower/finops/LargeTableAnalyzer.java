package io.dbtower.finops;

import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 큰 테이블·과다 인덱싱 신호 (D6) — operator.tableStats()의 크기만으로 두 종류의 낭비 신호를 낸다.
 *
 * - 큰 테이블: 총 크기(데이터+인덱스)가 임계 이상인 테이블 — 아카이빙·파티셔닝·콜드 스토리지로 비용을
 *   줄일 여지가 있는지 검토 신호. "크다 = 낭비"가 아니라 "여기부터 보라"는 신호다.
 * - 과다 인덱싱: 인덱스 크기가 데이터 크기를 넘어서는 테이블 — 인덱스가 데이터보다 큰 건 인덱스가 너무
 *   많거나 넓다는 신호다(쓰기·저장 비용). 어떤 인덱스가 실제로 미사용인지는 UnusedIndexAnalyzer가 본다.
 *
 * 절감액을 지어내지 않는다 — 크기라는 실측만 근거로, "검토 신호"까지만. 임계는 작은 테이블 노이즈를 걸러내는
 * 문턱이다. 5기종 모두 tableStats를 구현하므로 지원한다(MySQL은 인덱스 크기 = INDEX_LENGTH 등 기종 표기).
 */
@Component
public class LargeTableAnalyzer implements FinOpsAnalyzer {

    /** 큰 테이블 신호 임계 — 총 크기가 이 이상이면 후보(작은 테이블 노이즈 컷). 256 MB. */
    private static final long LARGE_TABLE_BYTES = 256L * 1024 * 1024;

    /** 과다 인덱싱 신호 하한 — 인덱스가 이보다 클 때만 본다(초소형 테이블의 비율 왜곡 방지). 8 MB. */
    private static final long OVER_INDEX_FLOOR_BYTES = 8L * 1024 * 1024;

    /** 크기 상위 조회 상한 */
    private static final int TOP_LIMIT = 50;

    @Override
    public String id() {
        return "large-table";
    }

    @Override
    public String title() {
        return "큰 테이블·과다 인덱싱 신호";
    }

    @Override
    public boolean supports(DbmsType type) {
        return true; // tableStats는 5기종 모두 구현
    }

    @Override
    public List<WasteCandidate> analyze(DatabaseInstance instance, DbmsOperator operator) {
        List<WasteCandidate> candidates = new ArrayList<>();
        for (TableStat t : operator.tableStats(TOP_LIMIT)) {
            long total = t.dataBytes() + t.indexBytes();
            if (total >= LARGE_TABLE_BYTES) {
                candidates.add(new WasteCandidate(
                        WasteKind.LARGE_TABLE, Severity.INFO, t.tableName(),
                        "총 " + FinOpsFormat.bytes(total) + " (데이터 " + FinOpsFormat.bytes(t.dataBytes())
                                + " + 인덱스 " + FinOpsFormat.bytes(t.indexBytes()) + "), 행수 약 " + t.rowCount() + ".",
                        "오래된 데이터 아카이빙·파티셔닝·콜드 스토리지로 비용을 줄일 여지가 있는지 검토한다."));
            }
            // 과다 인덱싱: 인덱스가 데이터보다 크고, 인덱스가 하한 이상일 때만(초소형 테이블 비율 왜곡 배제)
            if (t.indexBytes() > t.dataBytes() && t.dataBytes() > 0
                    && t.indexBytes() >= OVER_INDEX_FLOOR_BYTES) {
                candidates.add(new WasteCandidate(
                        WasteKind.OVER_INDEXED, Severity.INFO, t.tableName(),
                        "인덱스 " + FinOpsFormat.bytes(t.indexBytes()) + " > 데이터 "
                                + FinOpsFormat.bytes(t.dataBytes()) + " — 인덱스가 데이터보다 크다.",
                        "인덱스가 너무 많거나 넓다는 신호다. 미사용 인덱스 후보와 함께 인덱스 구성을 검토한다."));
            }
        }
        return candidates;
    }
}
