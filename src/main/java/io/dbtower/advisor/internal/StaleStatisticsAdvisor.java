package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 통계 미수집·오래된 테이블 점검 (Phase D2) — tableStats()가 돌려주는 행수·크기만으로 판정한다.
 *
 * 옵티마이저 통계는 tableStats의 rowCount 원천이기도 하다(기종별 통계 카탈로그 기반 추정치).
 * "데이터는 있는데(데이터 크기 > 0) 통계상 행수가 0"이면 통계가 아직 수집되지 않았거나 오래돼
 * 옵티마이저가 잘못된 플랜을 고를 위험이 있다 — 실행계획 급변(RegressionDetector가 잡는 rows 폭증)의
 * 흔한 배후다.
 *
 * 정직한 한계: 마지막 ANALYZE 시각은 tableStats에 없어(그 조회를 위해 operator 메서드를 늘리지 않는다)
 * "행수 0 + 데이터 존재"라는 보수적 신호만 쓴다 — 오래됨의 일부는 놓칠 수 있음을 detail에 명시한다.
 * 관계형 4기종만 지원한다. MongoDB는 $collStats가 통계 추정이 아니라 실측 카운트라 이 신호가 성립하지 않아
 * UNSUPPORTED로 표기한다.
 */
@Component
public class StaleStatisticsAdvisor implements Advisor {

    /** 데이터가 "의미 있게 존재"한다고 볼 최소 바이트 — 빈 테이블의 카탈로그 잡음을 배제(64KB) */
    private static final long MIN_DATA_BYTES = 64 * 1024;

    @Override
    public String id() {
        return "stale-statistics";
    }

    @Override
    public String title() {
        return "통계 미수집·오래된 테이블 후보";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type != DbmsType.MONGODB;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(operator.tableStats(200));
    }

    /** 순수 판정 — "데이터 크기 > 0인데 통계상 행수 0"인 테이블을 통계 미수집 후보로 본다. */
    public List<AdvisorFinding> evaluate(List<TableStat> tables) {
        List<AdvisorFinding> findings = new ArrayList<>();
        for (TableStat t : tables) {
            if (t.rowCount() == 0 && t.dataBytes() >= MIN_DATA_BYTES) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "통계 미수집 후보: " + t.tableName(),
                        ("데이터 크기 %,dB인데 통계상 행수가 0이다. 옵티마이저 통계가 수집 전이거나 오래돼 "
                                + "잘못된 플랜(불필요한 풀스캔·나쁜 조인 순서)을 유발할 수 있다. "
                                + "(마지막 ANALYZE 시각은 이 신호에 포함되지 않아 오래됨 일부는 놓칠 수 있음)")
                                .formatted(t.dataBytes()),
                        "해당 테이블에 통계 갱신을 실행한다(MySQL ANALYZE TABLE / PostgreSQL ANALYZE / "
                                + "SQL Server UPDATE STATISTICS / Oracle DBMS_STATS.GATHER_TABLE_STATS)."));
            }
        }
        return findings;
    }
}
