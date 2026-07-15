package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.StatsHealth;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 통계 수집 건강 점검 (심화 아크 5) — "수집이 조용히 거짓말하고 있지 않은가"의 실측 판정.
 *
 * DigestsSaturationAdvisor가 설정 위험(포화에 취약한 구성)을 보는 것과 짝으로, 이 Advisor는
 * 실측값(현재 포화율·이미 발생한 소실·PS 익명 부하)을 본다. 레퍼런스 밋업의 Lessons Learned 대응:
 * 레퍼런스는 80% 초과 시 자동 truncate했지만, DBTower는 대상 DB를 바꾸지 않으므로
 * 경보 + 명령 안내까지만 한다(PITR·gh-ost와 같은 생성·안내 모델).
 */
@Component
public class StatsCollectionAdvisor implements Advisor {

    /** 레퍼런스가 자동 truncate 기준으로 쓴 포화율 — 우리는 같은 기준으로 경보만 낸다 */
    static final double SATURATION_WARN_PCT = 80.0;
    /** PS 실행 합이 이 이상이면 "보이지 않는 부하"가 유의하다고 본다 */
    static final long PS_EXEC_WARN = 10_000;

    @Override
    public String id() {
        return "stats-collection-health";
    }

    @Override
    public String title() {
        return "쿼리 통계 수집 건강 (포화·소실·PS 사각)";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type == DbmsType.MYSQL || type == DbmsType.POSTGRESQL;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(instance.getType(), operator.statsHealth());
    }

    /** 순수 판정 — statsHealth 실측값만으로 결론 낸다(단위 테스트 대상). */
    public List<AdvisorFinding> evaluate(DbmsType type, StatsHealth h) {
        List<AdvisorFinding> findings = new ArrayList<>();
        if (!h.supported()) {
            return findings;   // supports가 걸렀어야 하지만, 실측 불가면 지어내지 않고 침묵
        }

        if (type == DbmsType.MYSQL) {
            // (1) 이미 소실이 발생했는가 — 발생했다면 신규 쿼리 감지가 부분 무력화된 상태다
            if (h.lostOrEvicted() > 0) {
                findings.add(new AdvisorFinding(Severity.CRITICAL,
                        "digest 소실 발생 — 신규 쿼리 통계가 이미 유실되고 있음 (lost=" + h.lostOrEvicted() + ")",
                        "Performance_schema_digest_lost=" + h.lostOrEvicted() + ". digest 테이블이 가득 차 "
                                + "새 유형의 쿼리가 통계에 잡히지 않는다 — Top Query·신규 쿼리 감지·회귀 감지가 "
                                + "이 쿼리들에 대해 눈이 먼 상태다.",
                        "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest 로 리셋하고 "
                                + "(누적 통계만 비움 — 데이터 무손실), performance_schema_digests_size 상향을 검토한다. "
                                + "실행은 사람이 한다 — DBTower는 대상 DB를 바꾸지 않는다."));
            }
            // (2) 포화 임박 — 레퍼런스의 80% 기준
            double used = h.usedPct();
            if (used >= SATURATION_WARN_PCT) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "digest 테이블 포화 임박 — %d/%d (%.1f%%)".formatted(h.statsRows(), h.statsLimit(), used),
                        "events_statements_summary_by_digest가 상한의 " + used + "%까지 찼다. 가득 차면 "
                                + "신규 쿼리 통계가 소실된다(소실은 위 CRITICAL로 별도 감지).",
                        "TRUNCATE 리셋 또는 performance_schema_digests_size 상향(기본 10,000 → 20,000)을 검토한다."));
            }
            // (3) PS 익명 부하 — digest에는 EXECUTE 문으로만 남아 Top Query에서 정체를 알 수 없다
            if (h.psExecutions() >= PS_EXEC_WARN) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "Prepared Statement 실행 %,d회 — Top Query에 보이지 않는 부하".formatted(h.psExecutions()),
                        "PS 실행은 digest에 EXECUTE 문으로만 집계돼(실측: 내부 쿼리 미집계) 쿼리별 통계에서 "
                                + "익명 부하가 된다. 현재 PS " + h.psInstances() + "개, 누적 실행 " + h.psExecutions() + "회.",
                        "performance_schema.prepared_statements_instances의 SQL_TEXT(원문 보존)로 정체를 확인한다. "
                                + "짧은 커넥션에서 매번 prepare→execute→close 하는 패턴이면 PS를 걷어내는 것이 "
                                + "통계 가시성·메모리(세션 로컬 캐시 중복) 양쪽에 낫다."));
            } else if (h.psInstances() > 0) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "Prepared Statement " + h.psInstances() + "개 사용 중 (실행 " + h.psExecutions() + "회)",
                        "PS 실행은 쿼리별 digest 통계에 잡히지 않는다 — 규모가 커지면 Top Query가 부하를 과소평가한다.",
                        "부하 비중이 커지면 prepared_statements_instances로 원문을 추적한다."));
            }
        }

        if (type == DbmsType.POSTGRESQL) {
            // PG는 포화 소실 대신 evict — 덜 쓰인 쿼리가 밀려나 저빈도 쿼리의 비교 신뢰도가 떨어진다
            if (h.lostOrEvicted() > 0) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "pg_stat_statements evict 발생 (dealloc=" + h.lostOrEvicted() + ", 누적)",
                        "고유 쿼리 종류가 pg_stat_statements.max(" + h.statsLimit() + ")를 넘어 덜 쓰인 쿼리가 "
                                + "밀려났다. 밀려난 쿼리는 시점 비교에서 '신규'로 오인되거나 베이스라인이 끊긴다.",
                        "pg_stat_statements.max 상향(재기동 필요)을 검토하거나, 저빈도 쿼리의 비교 결과를 "
                                + "해석할 때 evict 가능성을 감안한다."));
            }
            double used = h.usedPct();
            if (used >= SATURATION_WARN_PCT && h.lostOrEvicted() <= 0) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "pg_stat_statements 사용률 %.1f%% (%d/%d)".formatted(used, h.statsRows(), h.statsLimit()),
                        "상한에 접근 중 — 넘으면 evict가 시작된다(MySQL과 달리 소실이 아니라 교체지만, "
                                + "저빈도 쿼리의 비교 신뢰도는 떨어진다).",
                        "고유 쿼리 종류가 계속 늘면 pg_stat_statements.max 상향을 검토한다."));
            }
        }

        return findings;
    }
}
