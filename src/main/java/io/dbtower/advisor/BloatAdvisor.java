package io.dbtower.advisor;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.TableBloat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 블로트/autovacuum 지체 점검 (C-2, PostgreSQL 전용) — tableBloat()의 pg_stat_user_tables
 * 신호로 판정한다.
 *
 * autovacuum이 죽은 튜플(UPDATE/DELETE의 옛 버전)을 못 따라가면 테이블이 부풀어 스캔이 느려지고
 * 디스크를 낭비한다. dead ratio가 임계를 넘고 절대량도 의미 있게 크면 "autovacuum이 못 따라감"으로 본다.
 * pganalyze VACUUM Advisor·PMM이 1순위로 보는 항목이다.
 *
 * 정직한 한계: n_dead_tup은 통계 <b>추정치</b>라 실측 블로트가 아니다(pgstattuple 실측은 범위 밖).
 * "삭제 근거"가 아니라 "VACUUM/통계를 점검하라"는 신호다 — detail에 명시한다. PostgreSQL만 지원한다
 * (MySQL/InnoDB의 블로트는 의미가 달라 이 신호가 성립하지 않으므로 supports에서 제외).
 */
@Component
public class BloatAdvisor implements Advisor {

    /** dead ratio 임계 — 죽은 튜플이 전체의 이 비율을 넘으면 후보 */
    private static final double DEAD_RATIO_THRESHOLD = 0.2;
    /** 죽은 튜플 최소 절대량 — 작은 테이블의 높은 비율(잡음)을 배제 */
    private static final long MIN_DEAD_TUPLES = 10_000;
    /** ANALYZE 이후 변경 튜플이 이 수를 넘으면 통계 노후 후보 */
    private static final long STALE_MODS_THRESHOLD = 50_000;

    @Override
    public String id() {
        return "table-bloat";
    }

    @Override
    public String title() {
        return "테이블 블로트·autovacuum 지체 후보";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type == DbmsType.POSTGRESQL;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(operator.tableBloat(50));
    }

    /** 순수 판정 — dead ratio·절대량 임계와 ANALYZE 이후 변경량으로 후보를 뽑는다. */
    public List<AdvisorFinding> evaluate(List<TableBloat> tables) {
        List<AdvisorFinding> findings = new ArrayList<>();
        for (TableBloat t : tables) {
            if (t.deadRatio() >= DEAD_RATIO_THRESHOLD && t.deadTuples() >= MIN_DEAD_TUPLES) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "블로트 후보: " + t.tableName(),
                        ("죽은 튜플 %,d개 / 전체의 %.1f%% (추정치 — 실측 블로트 아님). autovacuum이 못 따라가 "
                                + "테이블이 부풀면 스캔이 느려지고 디스크를 낭비한다. 마지막 autovacuum: %s")
                                .formatted(t.deadTuples(), t.deadRatio() * 100, t.lastAutovacuum() == null
                                        ? "기록 없음" : t.lastAutovacuum()),
                        "이 테이블의 autovacuum 설정(임계·scale factor)이 워크로드를 못 따라가는지 점검한다. "
                                + "즉시 회수가 필요하면 수동 VACUUM을 고려한다(실행은 사람이 판단)."));
            } else if (t.modsSinceAnalyze() >= STALE_MODS_THRESHOLD) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "통계 노후 후보: " + t.tableName(),
                        ("마지막 ANALYZE 이후 %,d개 튜플이 변경됐다. 옵티마이저가 낡은 분포로 잘못된 플랜을 "
                                + "고를 수 있다(실행계획 급변의 배후).").formatted(t.modsSinceAnalyze()),
                        "통계 갱신(ANALYZE)을 고려한다. autovacuum의 analyze 임계 조정도 검토한다."));
            }
        }
        return findings;
    }
}
