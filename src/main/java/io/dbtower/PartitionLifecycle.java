package io.dbtower;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 월별 RANGE 파티션 수명주기 공용 로직 (V18 query_snapshot에서 V19 health_sample로 일반화).
 *
 * 볼륨 테이블의 보존 정리를 벌크 DELETE(느림 + dead tuple 블로트) 대신 파티션 DROP(즉시·블로트 0)
 * 으로 바꾸는 세 동작을 테이블 이름만 바꿔 재사용한다:
 * - 선생성: 이번 달·다음 달 파티션(멱등) — 정상 INSERT가 DEFAULT 안전망으로 새지 않게
 * - DROP: 월 전체가 보존 기한을 지난 파티션만, 이름 규약(<table>_yYYYYmMM) 파싱으로 보수적으로
 * - 판별: 파티셔닝 여부를 PG 카탈로그로 확인 — 아니면(H2 테스트·전환 전) 호출부가 DELETE 폴백
 *
 * 왜 루트 패키지인가: insight(query_snapshot)와 slo(health_sample)가 함께 쓰는 저장 인프라라
 * 어느 한 모듈에 두면 다른 모듈이 그 모듈을 참조하게 된다 — SchedulingConfig(LockProvider)와
 * 같은 이유로 공용 영역에 둔다. 테이블 이름은 호출부 상수로만 들어온다(사용자 입력 아님).
 */
@Component
public class PartitionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PartitionLifecycle.class);

    private static final Pattern PARTITION_NAME = Pattern.compile(".*_y(\\d{4})m(\\d{2})");

    private final JdbcTemplate jdbcTemplate;

    public PartitionLifecycle(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 테이블이 파티션드인가 — PG 카탈로그가 없는 환경(H2 테스트·전환 전)은 false 폴백. */
    public boolean isPartitioned(String table) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_partitioned_table pt
                                   JOIN pg_class c ON c.oid = pt.partrelid
                                   WHERE c.relname = ?)
                    """, Boolean.class, table));
        } catch (Exception e) {
            return false;
        }
    }

    /** 이번 달·다음 달 파티션 선생성(멱등) — 정상 INSERT 경로가 항상 월 파티션에 닿게 한다. */
    public void ensureUpcomingPartitions(String table) {
        YearMonth now = YearMonth.now();
        for (YearMonth month : List.of(now, now.plusMonths(1))) {
            jdbcTemplate.execute(createPartitionSql(table, month));
        }
    }

    /** 통째로 기한이 지난 월 파티션을 DROP — 즉시·블로트 없음. 걸친 파티션은 DELETE 몫으로 남긴다. */
    public int dropExpiredPartitions(String table, LocalDateTime cutoff) {
        List<String> children = jdbcTemplate.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """, String.class, table);
        int dropped = 0;
        for (String child : children) {
            if (droppable(table, child, cutoff)) {
                jdbcTemplate.execute("DROP TABLE " + child);
                log.info("파티션 DROP — {} (월 전체가 보존 기한 경과)", child);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * 파티션 이름(<table>_yYYYYmMM)에서 월을 읽어 그 달 전체가 cutoff 이전인지 판정한다.
     * 이름 규약을 안 따르는 자식(DEFAULT 파티션 등)은 건드리지 않는다 — DROP은 보수적으로.
     */
    public static boolean droppable(String table, String childName, LocalDateTime cutoff) {
        if (!childName.startsWith(table + "_y")) {
            return false;
        }
        Matcher m = PARTITION_NAME.matcher(childName);
        if (!m.matches()) {
            return false;
        }
        YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        LocalDate monthEnd = month.plusMonths(1).atDay(1);   // 파티션 상한(exclusive)
        return !monthEnd.atStartOfDay().isAfter(cutoff);
    }

    /** 월 파티션 생성 DDL(멱등) — 이름 규약은 droppable의 파싱과 한 쌍이다. */
    public static String createPartitionSql(String table, YearMonth month) {
        return "CREATE TABLE IF NOT EXISTS %s_y%04dm%02d PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                .formatted(table, month.getYear(), month.getMonthValue(), table,
                        month.atDay(1), month.plusMonths(1).atDay(1));
    }
}
