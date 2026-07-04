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
 * 위험 파라미터값 점검 (Phase D2) — parameters()(B6)가 돌려주는 설정값 중 기종별로 "알려진 위험값"을
 * 규칙으로 판정한다. 내구성을 끄는 값(fsync=off), 조용한 데이터 절단(sql_mode에 STRICT 없음),
 * 무제한 메모리 등 운영 사고로 직결되는 설정을 잡는다.
 *
 * 큐레이션된 기종만 지원한다(MySQL·PostgreSQL·SQL Server). Oracle·MongoDB는 근거 있는 위험값 규칙을
 * 아직 확정하지 못해 UNSUPPORTED로 정직하게 표기한다(임의 판정으로 오탐을 만들지 않는다).
 *
 * 판정은 순수 함수(evaluate)라 실 DB 없이 단위 테스트로 규칙을 고정한다.
 */
@Component
public class RiskyParameterAdvisor implements Advisor {

    @Override
    public String id() {
        return "risky-parameter";
    }

    @Override
    public String title() {
        return "위험 파라미터값";
    }

    @Override
    public boolean supports(DbmsType type) {
        return type == DbmsType.MYSQL || type == DbmsType.POSTGRESQL || type == DbmsType.MSSQL;
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(instance.getType(), operator.parameters());
    }

    /**
     * 순수 판정 — 기종과 파라미터 목록만으로 위험값을 찾는다.
     * 이름 조회는 소문자로 정규화해 비교한다(기종별 대소문자 표기 차이 흡수).
     */
    public List<AdvisorFinding> evaluate(DbmsType type, List<DbParameter> params) {
        Map<String, String> byName = params.stream()
                .collect(Collectors.toMap(p -> p.name().toLowerCase(), DbParameter::value, (a, b) -> a));
        return switch (type) {
            case MYSQL -> evaluateMySql(byName);
            case POSTGRESQL -> evaluatePostgres(byName);
            case MSSQL -> evaluateMsSql(byName);
            default -> List.of(); // supports()에서 걸러지므로 도달하지 않는다
        };
    }

    private List<AdvisorFinding> evaluateMySql(Map<String, String> p) {
        List<AdvisorFinding> findings = new ArrayList<>();
        // 내구성: 1이 아니면 커밋이 매번 디스크로 내려가지 않아 크래시 시 최대 1초 트랜잭션 유실
        str(p, "innodb_flush_log_at_trx_commit").ifPresent(v -> {
            if (!"1".equals(v.trim())) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "innodb_flush_log_at_trx_commit=" + v + " — 커밋 내구성 완화",
                        "1이 아니면 크래시 시 최근 커밋이 유실될 수 있다(0/2는 성능을 위한 내구성 트레이드오프).",
                        "내구성이 필요한 OLTP면 1로 되돌린다."));
            }
        });
        // 조용한 데이터 절단: STRICT 모드가 빠지면 범위 초과·잘못된 값이 경고 없이 잘려 저장된다
        str(p, "sql_mode").ifPresent(v -> {
            if (!v.toUpperCase().contains("STRICT_TRANS_TABLES") && !v.toUpperCase().contains("STRICT_ALL_TABLES")) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "sql_mode에 STRICT 모드 없음 — 조용한 데이터 절단 위험",
                        "STRICT가 없으면 범위를 넘는 값이 에러 없이 잘리거나 기본값으로 대체된다. 현재값: " + v,
                        "sql_mode에 STRICT_TRANS_TABLES를 추가한다."));
            }
        });
        // 연결 상한 극단값: 기본 151은 프로덕션에 낮을 수 있고, 과대값은 접속 폭주 시 메모리 고갈 위험
        integer(p, "max_connections").ifPresent(v -> {
            if (v <= 151) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "max_connections=" + v + " — 기본값 수준(프로덕션엔 낮을 수 있음)",
                        "MySQL 기본 상한(151)은 커넥션 풀이 큰 애플리케이션에서 'Too many connections'를 낸다.",
                        "예상 동시 연결 + 여유를 근거로 상향한다(무작정 크게 잡으면 메모리 위험)."));
            } else if (v >= 2000) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "max_connections=" + v + " — 과대 상한",
                        "연결당 스레드·버퍼 메모리가 붙어, 실제로 그만큼 접속되면 메모리가 고갈될 수 있다.",
                        "커넥션 풀로 동시 연결을 제한하고 상한을 현실적인 값으로 낮춘다."));
            }
        });
        return findings;
    }

    private List<AdvisorFinding> evaluatePostgres(Map<String, String> p) {
        List<AdvisorFinding> findings = new ArrayList<>();
        // 내구성 파괴: fsync=off는 크래시 시 데이터 파일이 손상될 수 있는 가장 위험한 설정
        str(p, "fsync").ifPresent(v -> {
            if (isOff(v)) {
                findings.add(new AdvisorFinding(Severity.CRITICAL,
                        "fsync=off — 크래시 시 데이터 손상 위험",
                        "fsync가 꺼지면 OS 크래시·전원 장애에서 데이터 파일이 복구 불가능하게 손상될 수 있다.",
                        "프로덕션에서는 반드시 on. 대량 적재 등 일시적으로 껐다면 즉시 되돌린다."));
            }
        });
        // 부분 페이지 쓰기 보호 해제
        str(p, "full_page_writes").ifPresent(v -> {
            if (isOff(v)) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "full_page_writes=off — 부분 쓰기 손상 위험",
                        "체크포인트 후 첫 페이지 변경의 전체 이미지를 남기지 않아, 크래시 시 페이지 파손 복구가 불가능할 수 있다.",
                        "특수한 스토리지 보장이 없다면 on으로 되돌린다."));
            }
        });
        // 커밋 동기화 완화 — 내구성 트레이드오프(치명적이진 않으나 알려야 함)
        str(p, "synchronous_commit").ifPresent(v -> {
            if (isOff(v)) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "synchronous_commit=off — 커밋 내구성 완화",
                        "크래시 시 최근 커밋 일부가 유실될 수 있다(데이터 손상은 아님). 의도된 성능 설정일 수 있다.",
                        "커밋 유실을 감내할 수 없는 데이터라면 on(또는 local)으로 둔다."));
            }
        });
        integer(p, "max_connections").ifPresent(v -> {
            if (v >= 1000) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "max_connections=" + v + " — 과대 상한",
                        "PostgreSQL은 연결당 프로세스라 수백을 넘으면 컨텍스트 스위칭·메모리 부담이 크다.",
                        "PgBouncer 같은 커넥션 풀러를 두고 상한을 낮춘다."));
            }
        });
        return findings;
    }

    private List<AdvisorFinding> evaluateMsSql(Map<String, String> p) {
        List<AdvisorFinding> findings = new ArrayList<>();
        // 무제한 서버 메모리 — 기본값 2147483647MB는 OS 메모리까지 빨아들여 서버 전체를 위태롭게 한다
        integer(p, "max server memory (mb)").ifPresent(v -> {
            if (v >= 2147483647) {
                findings.add(new AdvisorFinding(Severity.WARNING,
                        "max server memory (MB)=무제한(기본값) — OS 메모리 고갈 위험",
                        "상한을 두지 않으면 SQL Server가 OS와 다른 프로세스의 메모리까지 점유해 페이징·불안정을 유발할 수 있다.",
                        "물리 메모리에서 OS 몫을 뺀 값으로 상한을 설정한다."));
            }
        });
        // MAXDOP=0 — 대형 쿼리가 모든 코어를 잡아 OLTP 동시성을 해칠 수 있다
        integer(p, "max degree of parallelism").ifPresent(v -> {
            if (v == 0) {
                findings.add(new AdvisorFinding(Severity.INFO,
                        "max degree of parallelism=0 — 무제한 병렬(기본값)",
                        "큰 쿼리가 가용 코어를 모두 사용해 OLTP 워크로드의 응답성을 떨어뜨릴 수 있다.",
                        "코어 수·워크로드에 맞춰 MAXDOP을 명시적으로 설정한다(예: 4~8)."));
            }
        });
        return findings;
    }

    private static Optional<String> str(Map<String, String> p, String name) {
        return Optional.ofNullable(p.get(name)).filter(v -> !v.isBlank());
    }

    private static Optional<Long> integer(Map<String, String> p, String name) {
        return str(p, name).flatMap(RiskyParameterAdvisor::parseLong);
    }

    private static Optional<Long> parseLong(String v) {
        try {
            return Optional.of(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** off/0/false를 "꺼짐"으로 본다 — 기종·소스별 표기 차이(off vs 0)를 함께 흡수 */
    private static boolean isOff(String v) {
        String t = v.trim().toLowerCase();
        return t.equals("off") || t.equals("0") || t.equals("false");
    }
}
