package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 모니터링 계정 실명(조용한 저하) 점검 (Phase D2) — least-privilege.md의 PostgreSQL 함정을 코드로.
 *
 * least-privilege.md 기록: 다른 기종은 권한이 없으면 API가 에러를 내지만, PostgreSQL의
 * pg_stat_statements는 뷰 자체가 PUBLIC 조회 가능이라 권한이 부족하면 에러 없이 queryText만
 * '<insufficient privilege>'로 마스킹된다. 모니터링이 "동작하는 것처럼 보이는데 데이터가 없는"
 * 상태 — 관제가 눈이 먼 채 초록불을 켜 두는 가장 위험한 실패다.
 *
 * 이 신호는 queryStats() 결과에 그대로 드러나므로 operator 메서드를 늘리지 않고 판정한다.
 * least-privilege("과하지도 모자라지도 않게")의 '모자람' 쪽 위반이다. 이 조용한 저하 특성은
 * PostgreSQL 고유라(다른 기종은 권한 부족 시 명시적 에러) POSTGRESQL만 지원하고 나머지는 UNSUPPORTED.
 */
@Component
public class MonitoringPrivilegeAdvisor implements Advisor {

    /** pg_stat_statements가 권한 부족 시 queryText 자리에 넣는 마스킹 토큰 */
    private static final String INSUFFICIENT = "<insufficient privilege>";

    @Override
    public String id() {
        return "monitoring-privilege";
    }

    @Override
    public String title() {
        return "모니터링 계정 통계 접근(조용한 저하)";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type == DbmsType.POSTGRESQL;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(instance.getUsername(), operator.queryStats(50));
    }

    /** 순수 판정 — 상위 쿼리 통계에 마스킹 토큰이 섞여 있으면 계정 권한 부족으로 본다. */
    public List<AdvisorFinding> evaluate(String username, List<QueryStat> stats) {
        long masked = stats.stream()
                .filter(s -> INSUFFICIENT.equals(s.queryText()))
                .count();
        if (masked == 0) {
            return List.of();
        }
        return List.of(new AdvisorFinding(Severity.CRITICAL,
                "모니터링 계정이 쿼리 통계를 볼 수 없음(조용한 저하)",
                ("상위 쿼리 %d건의 텍스트가 '%s'로 마스킹됐다. 계정 '%s'에 통계 조회 권한이 없어 "
                        + "pg_stat_statements가 에러 없이 텍스트만 가린 상태 — 진단이 눈먼 채 동작하는 것처럼 보인다.")
                        .formatted(masked, INSUFFICIENT, username == null ? "?" : username),
                "모니터링 계정에 pg_read_all_stats 롤을 부여한다: GRANT pg_read_all_stats TO "
                        + (username == null ? "<monitor>" : username) + ";"));
    }
}
