package io.dbtower.advisor;

import io.dbtower.operator.DbParameter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MySQL digest 테이블 포화·실명 점검 (Phase D2) — operations.md 1·2절의 운영 규칙을 코드로.
 *
 * events_statements_summary_by_digest는 performance_schema_digests_size(기본 10,000)행까지만 담고,
 * 가득 차면 신규 쿼리 통계가 digest NULL 잡동사니로 합산돼 "신규 쿼리 감지"가 눈이 먼다.
 * performance_schema 자체가 꺼져 있으면 쿼리 통계 수집이 통째로 불가능하다.
 *
 * 정직한 한계: 실측 포화율(used_pct = COUNT(*) / digests_size)은 performance_schema 테이블의 행 수
 * COUNT가 필요한데, operator 인터페이스에 새 메서드를 더하지 않기 위해 이 Advisor는 parameters()로
 * 읽히는 "설정 위험"(수집 OFF·기본 상한·기본 digest 길이)까지만 판정한다. 측정된 포화율이 아니라
 * 포화에 취약한 구성을 지적하는 것임을 detail에 명시한다(위장 금지). MySQL 외 기종은 UNSUPPORTED.
 */
@Component
public class DigestsSaturationAdvisor implements Advisor {

    /** performance_schema_digests_size 기본값 — 이 이하이면 포화 취약 구성으로 본다 */
    private static final long DEFAULT_DIGESTS_SIZE = 10_000;
    /** performance_schema_max_digest_length 기본값(byte) — 긴 쿼리 병합의 원인 */
    private static final long DEFAULT_MAX_DIGEST_LENGTH = 1_024;

    @Override
    public String id() {
        return "digests-saturation";
    }

    @Override
    public String title() {
        return "MySQL digest 테이블 포화 위험";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type == DbmsType.MYSQL;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(operator.parameters());
    }

    /** 순수 판정 — MySQL SHOW GLOBAL VARIABLES 결과만으로 포화·실명 위험을 찾는다. */
    public List<AdvisorFinding> evaluate(List<DbParameter> params) {
        Map<String, String> p = params.stream()
                .collect(Collectors.toMap(x -> x.name().toLowerCase(), DbParameter::value, (a, b) -> a));
        List<AdvisorFinding> findings = new ArrayList<>();

        // performance_schema OFF면 query-stats·slow-queries·시점 비교가 통째로 눈이 먼다
        Optional<String> ps = Optional.ofNullable(p.get("performance_schema")).filter(v -> !v.isBlank());
        if (ps.isPresent() && isOff(ps.get())) {
            findings.add(new AdvisorFinding(Severity.CRITICAL,
                    "performance_schema=OFF — 쿼리 통계 수집 불가",
                    "performance_schema가 꺼져 있으면 digest 통계가 아예 쌓이지 않아 성능 진단 전체가 무력화된다.",
                    "my.cnf에 performance_schema=ON 설정 후 재기동한다(런타임 변경 불가)."));
            return findings; // 수집 자체가 꺼졌으면 아래 상한 지적은 의미가 없다
        }

        integer(p, "performance_schema_digests_size").ifPresent(size -> {
            if (size <= DEFAULT_DIGESTS_SIZE) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "digest 테이블 상한=" + size + " — 포화 시 신규 쿼리 통계 유실 위험",
                        ("설정값이 기본 상한(%d) 이하다. 워크로드의 고유 쿼리 종류가 이를 넘으면 신규 쿼리 통계가 "
                                + "digest NULL로 합산돼 신규 쿼리 감지가 눈이 먼다. (측정 포화율이 아니라 설정 위험 지적)")
                                .formatted(DEFAULT_DIGESTS_SIZE),
                        "performance_schema_digests_size를 20,000으로 상향하고, 실측 포화율이 80%를 넘으면 "
                                + "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest로 리셋한다."));
            }
        });

        integer(p, "performance_schema_max_digest_length").ifPresent(len -> {
            if (len <= DEFAULT_MAX_DIGEST_LENGTH) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "digest 길이 상한=" + len + "B — 긴 쿼리 병합 위험",
                        "digest는 앞 " + len + "byte까지만 보고 만들어, 앞부분이 같은 긴 쿼리들이 하나로 합쳐져 문제 쿼리를 특정하기 어렵다.",
                        "performance_schema_max_digest_length와 max_digest_length를 4096으로 상향한다(메모리 부담 작음)."));
            }
        });

        return findings;
    }

    private static Optional<Long> integer(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean isOff(String v) {
        String t = v.trim().toLowerCase();
        return t.equals("off") || t.equals("0") || t.equals("false");
    }
}
