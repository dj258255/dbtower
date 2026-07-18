package io.dbtower.operator.internal;

import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupPolicy.BackupType;
import io.dbtower.operator.model.StatsHealth;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.IndexUsage;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.PartitionInfo;
import io.dbtower.operator.PlanShapes;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.ReplicationSlot;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.RestoreSupport;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.operator.model.TableBloat;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.operator.model.TableDetail.DdlSource;
import io.dbtower.operator.model.TableStat;
import io.dbtower.operator.model.WaitEvent;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * PostgreSQL 어댑터.
 *
 * 통계 소스: pg_stat_statements (shared_preload_libraries 필요 — docker-compose에서 설정)
 * MySQL과 달리 쿼리 파싱 결과 기반으로 정규화하므로 digest 길이 이슈가 없다.
 */
public class PostgresOperator extends AbstractJdbcOperator {

    /** 통계 기반 슬로우 쿼리 판정 임계값(평균 수행시간 ms) */
    private static final double SLOW_MEAN_MS = 500.0;

    public PostgresOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
    }

    /** PostgreSQL 백업 = 호스트 CLI(pg_dump) 실행 모델. 비밀번호는 인자가 아니라 PGPASSWORD 환경변수로 */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupType.LOG) {
            return walBackup();
        }
        if (policy.type() == BackupType.PHYSICAL) {
            return physicalBackup();
        }
        Path out = Path.of(backupTools.backupDir(),
                "postgres-%s-%s.sql".formatted(safeFileName(instance.getName()), backupTimestamp()));
        return runCliBackup(renderCommand(backupTools.pgDumpCommand()),
                Map.of("PGPASSWORD", instance.getPassword()), out);
    }

    /**
     * 물리 전체 백업 (PHYSICAL) = pg_basebackup — PG 시점 복구의 진짜 앵커(공식 PITR 절차의 정석).
     * pg_dump(논리)는 WAL을 재생할 수 없어서, "물리 베이스 + WAL 세그먼트 + recovery_target_time"이
     * 완전한 체인이다. pg_basebackup은 replication 프로토콜 클라이언트라 서버 설정 변경이 필요 없다
     * (wal_level=replica·max_wal_senders는 기본값으로 충분, REPLICATION 권한만) — tar 포맷(-Ft)을
     * stdout(-D -)으로 받아 기존 수집 모델(stdout→파일) 그대로 저장한다. -X none: WAL은 세그먼트
     * 수집(walBackup)과 조합하는 것이 체인의 정석이므로 베이스에 중복 포함하지 않는다.
     */
    private BackupResult physicalBackup() {
        if (backupTools.pgBaseBackupCommand() == null || backupTools.pgBaseBackupCommand().isBlank()) {
            throw new UnsupportedOperationException(
                    "물리 백업 명령 미설정(dbtower.backup.pg-basebackup-command) — "
                            + "pg_basebackup(-Ft -D - -X none, REPLICATION 권한)을 지정하면 동작한다");
        }
        Path out = Path.of(backupTools.backupDir(),
                "postgres-basebackup-%s-%s.tar".formatted(safeFileName(instance.getName()), backupTimestamp()));
        return runCliBackup(renderCommand(backupTools.pgBaseBackupCommand()),
                Map.of("PGPASSWORD", instance.getPassword()), out);
    }

    /**
     * 로그 백업 (Phase 2) = pg_switch_wal()로 현재 WAL 세그먼트를 닫고, 닫힌 세그먼트를 수집한다.
     * MySQL의 FLUSH BINARY LOGS와 같은 결 — 서버 설정 변경이 아니라 로그 로테이션(백업 행위의 일부)이다.
     * pg_walfile_name(pg_switch_wal())은 경계 LSN에서 "직전 세그먼트" 이름을 준다(공식 문서 명세).
     *
     * 게이트(정직): wal_level=minimal이면 WAL에 복구 재료가 없다 → UNSUPPORTED. pg_switch_wal은
     * superuser(또는 EXECUTE grant) 필요 — 권한 부족도 사유와 함께 UNSUPPORTED. 수집 명령 미설정도 동일.
     *
     * 정석 대비 위치(웹 확인): 상시 아카이빙의 정석은 archive_command(서버 구성) 또는
     * pg_receivewal(REPLICATION 권한, 스트리밍 데몬)이다. 폴러 주기의 원샷 모델에는 switch+수집이
     * 대응하며, 세그먼트 사이 간격(주기 사이 crash)은 스트리밍보다 유실 창이 크다 — note에 명시한다.
     */
    private BackupResult walBackup() {
        String walLevel = jdbc().queryForObject("SHOW wal_level", String.class);
        if ("minimal".equalsIgnoreCase(walLevel)) {
            throw new UnsupportedOperationException(
                    "wal_level=minimal — WAL에 복구 재료가 기록되지 않는다(대상 서버 설정이라 바꾸지 않는다). "
                            + "replica 이상이면 로그 백업이 동작한다");
        }
        if (backupTools.pgWalCommand() == null || backupTools.pgWalCommand().isBlank()) {
            throw new UnsupportedOperationException(
                    "WAL 수집 명령 미설정(dbtower.backup.pg-wal-command) — 상시 아카이빙이 필요하면 "
                            + "pg_receivewal(스트리밍)이 정석이다");
        }
        String justClosed;
        try {
            justClosed = jdbc().queryForObject("SELECT pg_walfile_name(pg_switch_wal())", String.class);
        } catch (DataAccessException e) {
            // superuser 아님 등 권한 부족 — 실패가 아니라 "이 계정으론 못 한다"를 정직하게
            throw new UnsupportedOperationException(
                    "pg_switch_wal 실행 불가(superuser 또는 EXECUTE grant 필요): " + e.getMessage());
        }
        // 정석과의 위치(문헌 확인): 무결한 연속 아카이브의 정석은 archive_command/pg_receivewal이다 —
        // 서버와 수집이 조율돼 세그먼트가 아카이브 확인 전에 재활용되지 않는다. 이 원샷 수집은 조율이
        // 없어, 주기 사이에 채워진 세그먼트가 재활용되면 구멍이 날 수 있다. 그래서 (1) pg_ls_waldir로
        // 방금 닫힌 것 이하의 미수집 세그먼트를 전부 보충 수집하고(멱등), (2) 그래도 남는 재활용 경합
        // 한계는 감추지 않는다 — 세그먼트 이름이 순차 hex라 수집본만으로 갭 검출이 가능하다.
        List<String> candidates;
        try {
            candidates = jdbc().query(
                    "SELECT name FROM pg_ls_waldir() WHERE name ~ '^[0-9A-F]{24}$' AND name <= ? ORDER BY name",
                    (rs, i) -> rs.getString("name"), justClosed);
        } catch (DataAccessException e) {
            candidates = List.of(justClosed);   // pg_ls_waldir 권한 부족(pg_monitor 필요) — 방금 닫힌 것만
        }
        long totalBytes = 0;
        String lastLocation = null;
        int collected = 0;
        for (String segment : candidates) {
            if (alreadyCollected("postgres-wal", segment)) {
                continue;
            }
            Path out = Path.of(backupTools.backupDir(),
                    "postgres-wal-%s-%s-%s".formatted(safeFileName(instance.getName()), backupTimestamp(), segment));
            List<String> cmd = new ArrayList<>(renderCommand(backupTools.pgWalCommand()));
            cmd.add(segment);
            BackupResult one = runCliBackup(cmd, Map.of("PGPASSWORD", instance.getPassword()), out);
            totalBytes += Math.max(0, one.bytes());
            lastLocation = one.location();
            collected++;
        }
        if (collected == 0) {
            throw new OperatorException("수집할 새 WAL 세그먼트가 없습니다(전부 이미 수집됨)");
        }
        return new BackupResult(lastLocation, totalBytes);
    }

    /** 이 세그먼트가 이미 로컬 산출물로 수집됐는가 — 파일명 접미(-<segment>)로 판정(멱등 수집). */
    private boolean alreadyCollected(String prefix, String segment) {
        try (var stream = Files.list(Path.of(backupTools.backupDir()))) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith("-" + segment));
        } catch (java.io.IOException e) {
            return false;   // 조회 실패면 재수집(중복이 유실보다 낫다)
        }
    }

    /**
     * PG PITR 안내 (Phase 2) — 앵커가 물리(basebackup)면 공식 PITR 절차 그대로, 논리(pg_dump)면
     * "WAL을 재생할 수 없다"는 한계 명시가 본체다(지어낸 절차 금지). 앵커 종류는 산출물 파일명
     * 규약(postgres-basebackup-*)으로 판정한다.
     */
    @Override
    public String pitrRestoreGuide(String fullLocation, List<String> logLocations, String targetTime) {
        boolean physicalAnchor = fullLocation != null && fullLocation.contains("basebackup");
        if (!physicalAnchor) {
            return """
                    -- PostgreSQL 시점 복구 안내 (정직한 한계 포함)
                    -- 주의: 현재 앵커(%s)는 pg_dump 논리 덤프라 WAL을 재생할 수 없다.
                    -- 진짜 시점 복구가 필요하면 PHYSICAL 타입(pg_basebackup)으로 백업하라 — 그러면
                    -- 이 안내문이 공식 PITR 절차(베이스 해체 + WAL 배치 + recovery_target_time)로 바뀐다.
                    -- 수집된 WAL 세그먼트(%d개)는 물리 베이스와 조합할 때 쓰인다."""
                    .formatted(fullLocation, logLocations.size());
        }
        return """
                # PostgreSQL 시점 복구 안내 (공식 PITR 절차 — 반드시 격리된 복구 디렉터리에서. e2e 검증된 문안)
                # 1) 물리 베이스백업 해체
                mkdir -p /restore/data && tar -xf %s -C /restore/data && chmod 700 /restore/data
                # 2) 수집된 WAL 세그먼트(%d개)를 아카이브 디렉터리에 배치
                #    (pg_wal 직접 배치가 아니라 restore_command 모델 — recovery 모드는 restore_command가 필수임을 실측)
                #    %s
                mkdir -p /restore/data/wal_archive && cp <수집된 WAL들, 파일명은 세그먼트명> /restore/data/wal_archive/
                # 3) 복구 지시 — recovery.signal + restore_command + 목표 시점
                touch /restore/data/recovery.signal
                cat >> /restore/data/postgresql.auto.conf <<'EOF'
                restore_command = 'cp /restore/data/wal_archive/%%f %%p'
                recovery_target_time = '%s'
                recovery_target_action = 'promote'
                EOF
                # 4) 이 데이터 디렉터리로 서버 기동 → 목표 시점 직전 트랜잭션까지 재생 후 승격
                #    (로그에 "recovery stopping before commit ..."이 정지 지점을 증언한다)"""
                .formatted(fullLocation, logLocations.size(),
                        String.join("\n                #    ", logLocations), targetTime);
    }

    /**
     * PostgreSQL 복원 검증 = pg_dump 평문 덤프를 격리된 임시 DB에 psql로 복원.
     * 단일 DB 평문 덤프는 CREATE DATABASE/\connect가 없어 연결된 DB(= 임시 DB)로만 적재된다 —
     * 원본은 건드리지 않는다. ON_ERROR_STOP=1로 한 문장이라도 실패하면 종료코드가 비0이 되어 FAILED.
     * 마지막에 임시 DB를 FORCE로 삭제(잔여 연결이 있어도 정리되게).
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        Path dump = Path.of(location);
        if (!Files.isRegularFile(dump)) {
            return RestoreVerification.failed("덤프 파일을 찾을 수 없습니다: " + location);
        }
        String target = RestoreSupport.verifyTargetName();
        RestoreSupport.requireSafeName(target);
        // 컨테이너 로컬 접속은 trust라 비밀번호가 필요 없지만, 다른 환경 대비 PGPASSWORD도 실어 둔다(무해)
        Map<String, String> env = Map.of("PGPASSWORD", instance.getPassword());
        List<String> base = renderCommand(backupTools.pgRestoreCommand());
        boolean created = false;
        try {
            RestoreSupport.ExecResult create = RestoreSupport.exec(RestoreSupport.concat(base,
                    "-d", "postgres", "-c", "CREATE DATABASE \"" + target + "\""), env, null);
            if (!create.ok()) {
                return RestoreVerification.failed("임시 DB 생성 실패: " + create.errorTail());
            }
            created = true;
            byte[] sql;
            try {
                sql = Files.readAllBytes(dump);
            } catch (java.io.IOException e) {
                return RestoreVerification.failed("덤프 파일 읽기 실패: " + e.getMessage());
            }
            RestoreSupport.ExecResult load = RestoreSupport.exec(
                    RestoreSupport.concat(base, "-d", target), env, sql);
            if (!load.ok()) {
                return RestoreVerification.failed("덤프 복원 실패: " + load.errorTail());
            }
            RestoreSupport.ExecResult count = RestoreSupport.exec(RestoreSupport.concat(base, "-d", target,
                    "-tAc", "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"),
                    env, null);
            return RestoreVerification.verified(
                    "임시 DB로 덤프 복원 성공 (psql, target=" + target + ")",
                    RestoreSupport.parseCount(count));
        } finally {
            if (created) {
                RestoreSupport.exec(RestoreSupport.concat(base, "-d", "postgres", "-c",
                        "DROP DATABASE IF EXISTS \"" + target + "\" WITH (FORCE)"), env, null);
            }
        }
    }

    @Override
    protected String jdbcUrl() {
        // useTls면 require — RDS rds.force_ssl 같은 TLS 강제 환경 대응. 미지정 시 드라이버 기본(prefer).
        String ssl = instance.isUseTls() ? "&sslmode=require" : "";
        return "jdbc:postgresql://%s:%d/%s?connectTimeout=3&socketTimeout=15%s"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName(), ssl);
    }

    @Override
    protected String versionSql() {
        return "SELECT version()";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // pg_stat_statements는 클러스터 전역 뷰라 dbid로 현재 DB만 필터해야 한다.
        // 안 하면 같은 클러스터의 다른 데이터베이스 쿼리까지 섞여 통계가 오염된다.
        String sql = """
                SELECT queryid::text AS query_id, query, calls,
                       total_exec_time AS total_ms, rows
                FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                ORDER BY total_exec_time DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new QueryStat(
                            rs.getString("query_id"),
                            rs.getString("query"),
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getLong("rows")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    /** p95의 표준정규 z값 — 정규분포에서 상위 5% 경계. */
    static final double Z95 = 1.645;

    /** p99의 표준정규 z값 — 정규분포에서 상위 1% 경계. */
    static final double Z99 = 2.326;

    /**
     * 레이턴시 백분위 (D4a + B-4 "있으면 승격") — 기본 경로는 pg_stat_statements의 mean+z×stddev
     * <b>근사(ESTIMATED)</b>다. pg_stat_statements에는 백분위 원자료가 없기 때문이다. 다만 확장
     * <b>pg_stat_monitor</b>가 설치돼 있으면 그쪽이 resp_calls(응답시간 버킷별 호출 수) 히스토그램을
     * 제공하므로, 이를 보간해 <b>NATIVE_HISTOGRAM</b>으로 승격한다(HypoPG·MSSQL Query Store와 같은
     * "게이트=있으면 승격, 없으면 정직한 스킵" 패턴).
     *
     * <p>게이트(pg_extension 존재 확인) 미통과, 또는 승격 과정의 어떤 실패(버전차/권한/빈 결과)든
     * <b>조용히 ESTIMATED로 폴백</b>한다 — 승격 실패를 통과로 위장하지 않고 정직하게 추정치를 낸다.
     * 데모 컨테이너에는 pg_stat_monitor가 없을 가능성이 높아, 라이브 관측 동작은 대개 ESTIMATED 유지다.
     */
    @Override
    public List<LatencyPercentile> latencyPercentiles(int limit) {
        // 게이트: 확장이 없으면 기존 ESTIMATED 경로를 그대로(동작 불변) 실행한다
        if (!hasPgStatMonitor()) {
            return estimatedFromStatStatements(limit);
        }
        try {
            List<LatencyPercentile> promoted = histogramFromStatMonitor(limit);
            // 표본이 없거나(빈 히스토그램) 경계를 못 읽으면 승격 포기 → 추정치로 내려간다
            if (promoted != null && !promoted.isEmpty()) {
                return promoted;
            }
        } catch (DataAccessException e) {
            // resp_calls/range() 조회 실패(버전차·권한) — 정직하게 ESTIMATED로 폴백
        }
        return estimatedFromStatStatements(limit);
    }

    /**
     * 기존 D4a 경로 — pg_stat_statements의 mean_exec_time + z×stddev_exec_time 정규분포 근사(ESTIMATED).
     * p95는 z=1.645, p99는 z=2.326. <b>이것은 진짜 백분위가 아니다.</b> 실제 레이턴시 분포는 오른쪽 꼬리가
     * 두꺼워(락 대기·GC·IO 스파이크) 정규분포보다 치우쳐, 이 근사는 대개 실제 p95/p99를 <b>과소평가</b>한다.
     * source=ESTIMATED로 실측(NATIVE/COMPUTED/NATIVE_HISTOGRAM)과 반드시 구분해 표기한다.
     */
    private List<LatencyPercentile> estimatedFromStatStatements(int limit) {
        // stddev_exec_time은 pg_stat_statements가 제공하는 표준편차 — 이걸 근사의 재료로 쓴다
        String sql = """
                SELECT queryid::text AS query_id, query,
                       mean_exec_time, stddev_exec_time
                FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                ORDER BY total_exec_time DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> {
                        double mean = rs.getDouble("mean_exec_time");
                        double stddev = rs.getDouble("stddev_exec_time");
                        return new LatencyPercentile(
                                rs.getString("query_id"),
                                rs.getString("query"),
                                estimate(mean, stddev, Z95),
                                estimate(mean, stddev, Z99),
                                LatencyPercentile.ESTIMATED);
                    },
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 레이턴시 백분위 근사 실패: " + e.getMessage(), e);
        }
    }

    /** 게이트 — pg_stat_monitor 확장이 설치돼 있는지만 확인(존재 확인, 상태 변경 없음). 조회 실패면 없음으로 간주. */
    private boolean hasPgStatMonitor() {
        try {
            Integer found = jdbc().query(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_monitor'",
                    rs -> rs.next() ? 1 : 0);
            return found != null && found == 1;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /**
     * 승격 경로 — pg_stat_monitor의 resp_calls(버킷별 호출 수)를 range()가 준 시간 경계로 보간해
     * NATIVE_HISTOGRAM 백분위를 낸다. pg_stat_monitor는 버킷(bucket) 시간창으로 데이터를 순환 저장하므로
     * 같은 queryid가 여러 bucket 행으로 나뉘어 나올 수 있다 — queryid로 묶어 resp_calls를 <b>배열 요소별로
     * 합산</b>한 뒤, 공통 경계로 {@link HistogramPercentile#interpolate}를 호출한다. 정렬은 calls 상위 N.
     *
     * <p>경계를 못 읽거나(빈 range) 표본이 없으면 null/빈 목록을 돌려 호출자가 ESTIMATED로 폴백하게 한다.
     */
    private List<LatencyPercentile> histogramFromStatMonitor(int limit) {
        double[][] bounds = respCallBounds();
        if (bounds == null) {
            return null; // 경계를 못 읽음 → 승격 포기 신호
        }
        double[] lower = bounds[0];
        double[] upper = bounds[1];

        // resp_calls를 queryid별로 요소합산해야 하므로 현재 DB의 원시 행을 모두 가져온다(버킷 순환 포함)
        String sql = """
                SELECT queryid::text AS query_id, query, resp_calls, calls
                FROM pg_stat_monitor
                WHERE datname = current_database()
                """;
        List<MonitorRow> rows = jdbc().query(sql, (rs, i) -> new MonitorRow(
                rs.getString("query_id"),
                rs.getString("query"),
                toLongArray(rs.getArray("resp_calls")),
                rs.getLong("calls")));

        // queryid별 그룹: resp_calls 요소합산, calls 누적(정렬용), query 텍스트는 첫 행 것을 유지
        LinkedHashMap<String, Agg> byId = new LinkedHashMap<>();
        for (MonitorRow r : rows) {
            if (r.queryId() == null || r.respCalls() == null) {
                continue;
            }
            Agg agg = byId.computeIfAbsent(r.queryId(), k -> new Agg(r.query()));
            agg.buckets = (agg.buckets == null)
                    ? r.respCalls()
                    : sumElementwise(agg.buckets, r.respCalls());
            agg.calls += r.calls();
        }

        // calls 상위 N만 승격 대상으로 정렬
        List<Map.Entry<String, Agg>> ranked = new ArrayList<>(byId.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue().calls, a.getValue().calls));

        List<LatencyPercentile> out = new ArrayList<>();
        for (Map.Entry<String, Agg> e : ranked) {
            if (out.size() >= limit) {
                break;
            }
            Agg agg = e.getValue();
            // interpolate는 counts.length <= 경계 길이를 전제 — 경계 길이에 맞춰 절단/패딩
            long[] counts = fitLength(agg.buckets, lower.length);
            Double p95 = HistogramPercentile.interpolate(lower, upper, counts, 0.95);
            Double p99 = HistogramPercentile.interpolate(lower, upper, counts, 0.99);
            out.add(new LatencyPercentile(
                    e.getKey(), agg.query,
                    p95 == null ? null : round2(p95),
                    p99 == null ? null : round2(p99),
                    LatencyPercentile.NATIVE_HISTOGRAM));
        }
        return out;
    }

    /** pg_stat_monitor 한 행의 원시 값(queryid·query·resp_calls 버킷·calls). */
    private record MonitorRow(String queryId, String query, long[] respCalls, long calls) {
    }

    /** queryid별 누적 상자 — resp_calls 요소합산 결과와 정렬용 calls 합, 대표 query 텍스트. */
    private static final class Agg {
        final String query;
        long[] buckets;
        long calls;

        Agg(String query) {
            this.query = query;
        }
    }

    /**
     * SELECT range() — pg_stat_monitor가 resp_calls 각 버킷의 시간 범위 문자열 배열("(low - high)", ms)을
     * 준다. 이를 파싱해 lowerBounds/upperBounds(ms) 두 배열로 만든다. 비었거나 널이면 null(승격 포기 신호).
     */
    private double[][] respCallBounds() {
        String[] ranges = jdbc().query("SELECT range() AS r", rs -> {
            if (!rs.next()) {
                return null;
            }
            java.sql.Array arr = rs.getArray("r");
            if (arr == null) {
                return null;
            }
            Object[] elems = (Object[]) arr.getArray();
            String[] out = new String[elems.length];
            for (int i = 0; i < elems.length; i++) {
                out[i] = elems[i] == null ? null : elems[i].toString();
            }
            return out;
        });
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        double[] lower = new double[ranges.length];
        double[] upper = new double[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            double[] b = parseRange(ranges[i]);
            lower[i] = b[0];
            upper[i] = b[1];
        }
        return new double[][]{lower, upper};
    }

    /** resp_calls 각 버킷 하한/상한을 담은 범위 문자열 하나에서 숫자를 뽑는다. */
    private static final java.util.regex.Pattern RANGE_NUM =
            java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * range() 원소 "(low - high)"에서 하한·상한(ms)을 뽑는다. 괄호·공백 변형("(0-3)", "( 0 - 3 )")을
     * 방어하려고 숫자 토큰만 순서대로 취한다. 숫자가 둘 미만이면 파싱 불가로 IllegalArgumentException —
     * 호출자(승격 경로)는 이를 잡아 ESTIMATED로 폴백한다.
     */
    static double[] parseRange(String range) {
        if (range == null) {
            throw new IllegalArgumentException("range() 원소가 null입니다");
        }
        java.util.regex.Matcher m = RANGE_NUM.matcher(range);
        double[] out = new double[2];
        int n = 0;
        while (n < 2 && m.find()) {
            out[n++] = Double.parseDouble(m.group());
        }
        if (n < 2) {
            throw new IllegalArgumentException("range() 경계 파싱 실패(숫자 2개 필요): " + range);
        }
        return out;
    }

    /** 두 resp_calls 버킷 배열을 요소별로 합산(순환 bucket 행 병합용). 길이가 다르면 긴 쪽에 맞춰 합산. */
    static long[] sumElementwise(long[] a, long[] b) {
        int n = Math.max(a.length, b.length);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
        }
        return out;
    }

    /** 버킷 배열을 경계 길이에 맞춰 절단/0패딩 — interpolate가 counts.length ≤ 경계 길이를 전제하므로. */
    static long[] fitLength(long[] counts, int len) {
        if (counts.length == len) {
            return counts;
        }
        long[] out = new long[len];
        System.arraycopy(counts, 0, out, 0, Math.min(counts.length, len));
        return out;
    }

    /** java.sql.Array(text[]/numeric[])를 long[]로. 원소는 문자열화 후 정수 파싱(소수면 반올림). null이면 null. */
    private static long[] toLongArray(java.sql.Array sqlArray) {
        if (sqlArray == null) {
            return null;
        }
        try {
            Object[] elems = (Object[]) sqlArray.getArray();
            long[] out = new long[elems.length];
            for (int i = 0; i < elems.length; i++) {
                out[i] = elems[i] == null ? 0L : Math.round(Double.parseDouble(elems[i].toString().trim()));
            }
            return out;
        } catch (java.sql.SQLException | NumberFormatException e) {
            return null; // 파싱 불가 → 이 행은 건너뛰게(호출자에서 null 스킵)
        }
    }

    /** 소수 둘째 자리 반올림 — 히스토그램 보간 결과 표기용. */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 정규분포 근사 백분위 = mean + z×stddev (음수는 0으로 클램프). 소수 둘째 자리로 반올림. */
    static double estimate(double mean, double stddev, double z) {
        double v = Math.max(0, mean + z * stddev);
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 파티션 조회 (D5) — 선언적 파티셔닝(PostgreSQL 10+) 기준. pg_partitioned_table(부모)에서 파티션
     * 전략(partstrat: r/l/h)을, pg_inherits로 자식 파티션을, pg_class에서 자식별 이름·경계·행수·크기를 모은다.
     * boundary는 pg_get_expr(relpartbound)로 "FOR VALUES FROM ... TO ..." 같은 실제 경계 정의를 얻는다.
     * 상속(테이블 상속)을 이용한 구식 파티셔닝은 pg_partitioned_table에 없어 잡히지 않는다(선언적만). 읽기 전용.
     */
    @Override
    public List<PartitionInfo> partitions(int limit) {
        String sql = """
                SELECT parent.relname AS table_name,
                       child.relname  AS partition_name,
                       pt.partstrat   AS strat,
                       pg_get_partkeydef(parent.oid)          AS part_expr,
                       pg_get_expr(child.relpartbound, child.oid) AS boundary,
                       child.reltuples::bigint                AS row_count,
                       pg_total_relation_size(child.oid)      AS size_bytes
                FROM pg_partitioned_table pt
                JOIN pg_class parent   ON parent.oid = pt.partrelid
                JOIN pg_inherits inh   ON inh.inhparent = parent.oid
                JOIN pg_class child    ON child.oid = inh.inhrelid
                JOIN pg_namespace ns   ON ns.oid = parent.relnamespace
                WHERE ns.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY parent.relname, child.relname
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new PartitionInfo(
                            rs.getString("table_name"),
                            rs.getString("partition_name"),
                            partStrat(rs.getString("strat")),
                            rs.getString("part_expr"),
                            rs.getString("boundary"),
                            rs.getObject("row_count", Long.class),
                            rs.getObject("size_bytes", Long.class)),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 파티션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 인덱스 사용 통계 (D6) — pg_stat_user_indexes.idx_scan이 이 인덱스로 시작된 스캔 누적 횟수다.
     * idx_scan=0이면 통계 리셋 이후 한 번도 안 쓰인 미사용 후보. pg_index.indisunique로 유니크/PK
     * 뒷받침 인덱스를 구분해(제약 유지에 필요할 수 있어 후보에서 제외되도록) 표시한다. 크기는
     * pg_relation_size(indexrelid). 사용 적은 순으로 정렬해 미사용 후보가 앞에 오게 한다. 읽기 전용.
     */
    @Override
    public List<IndexUsage> indexUsage(int limit) {
        String sql = """
                SELECT s.relname AS table_name, s.indexrelname AS index_name,
                       s.idx_scan AS scan_count,
                       pg_relation_size(s.indexrelid) AS size_bytes,
                       ix.indisunique AS is_unique
                FROM pg_stat_user_indexes s
                JOIN pg_index ix ON ix.indexrelid = s.indexrelid
                ORDER BY s.idx_scan ASC, pg_relation_size(s.indexrelid) DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new IndexUsage(
                            rs.getString("table_name"),
                            rs.getString("index_name"),
                            rs.getObject("scan_count", Long.class),
                            rs.getObject("size_bytes", Long.class),
                            rs.getBoolean("is_unique"),
                            IndexUsage.NATIVE),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 인덱스 사용 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /** pg_partitioned_table.partstrat 한 글자 코드를 사람이 읽을 이름으로 (r=RANGE, l=LIST, h=HASH). */
    private static String partStrat(String code) {
        return switch (code == null ? "" : code) {
            case "r" -> "RANGE";
            case "l" -> "LIST";
            case "h" -> "HASH";
            default -> code;
        };
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // PG는 로그 파일 파싱 대신 pg_stat_statements의 평균 수행시간으로 판정한다
        String sql = """
                SELECT query, mean_exec_time, rows
                FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                  AND mean_exec_time > ?
                ORDER BY mean_exec_time DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new SlowQuery(
                            rs.getString("query"),
                            rs.getDouble("mean_exec_time"),
                            rs.getLong("rows"),
                            LocalDateTime.now().toString()),
                    SLOW_MEAN_MS, limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // n_live_tup은 autovacuum 통계 기반 추정치
        String sql = """
                SELECT relname,
                       n_live_tup,
                       pg_relation_size(relid) AS data_bytes,
                       pg_indexes_size(relid) AS index_bytes
                FROM pg_stat_user_tables
                ORDER BY pg_total_relation_size(relid) DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new TableStat(
                            rs.getString("relname"),
                            rs.getLong("n_live_tup"),
                            rs.getLong("data_bytes"),
                            rs.getLong("index_bytes")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 테이블 상세 (심화 아크 3 — 테이블 상세 정보). 기본 통계 + 인덱스(타입·카디널리티) +
     * 재구성 DDL을 한 번에. 전부 읽기 전용이고, 테이블명은 가능한 자리마다 파라미터 바인딩한다. 다만
     * reconstructDdl은 식별자를 텍스트로 이어 붙이므로 requireIdentifier로 먼저 검증한다(주입 방어).
     *
     * PostgreSQL 특유의 한계를 값과 note에 정직히 반영한다:
     * - engine: 테이블별 스토리지 엔진 개념이 없다 → null
     * - createdAt: 카탈로그에 테이블 생성 시각을 저장하지 않는다 → null
     * - ddl: 단일 CREATE 명령이 없어 PostgreSQL 자체 정의 함수(pg_get_constraintdef·pg_get_indexdef)로
     *   컬럼·PK·FK·CHECK·인덱스까지 재구성 → RECONSTRUCTED(원문 위장 금지, 그러나 근사가 아닌 정확한 재조립).
     *   트리거·파티션 정의만 담지 못하며, 실제로 있을 때만 note에 명시한다.
     * - cardinality: 인덱스별 네이티브 카디널리티가 없어 선두 컬럼 pg_stats.n_distinct로 추정
     */
    @Override
    public TableDetail tableDetail(String tableName) {
        TableDetailSupport.requireIdentifier(tableName);
        try {
            // 기본 통계 — reltuples(통계 추정 행수), pg_table_size(TOAST 포함 데이터), pg_indexes_size.
            // to_regclass가 아니라 pg_class JOIN으로 잡되 시스템 스키마는 제외한다(describeSchema와 동일 원칙).
            // 파티션 부모(relkind='p')는 자체 저장소가 없어 reltuples=-1·크기 0으로 나온다 — pg_partition_tree로
            // 리프 파티션을 합산해야 실제 값이다. FILTER(reltuples>=0)는 미ANALYZE 리프(-1)를 합산에서 제외하고,
            // 전 리프가 미ANALYZE면 합계 NULL → -1(미확보 정직).
            String statsSql = """
                    SELECT c.oid,
                           CASE WHEN c.relkind = 'p'
                                THEN (SELECT COALESCE(sum(pc.reltuples) FILTER (WHERE pc.reltuples >= 0), -1)::bigint
                                      FROM pg_partition_tree(c.oid) pt
                                      JOIN pg_class pc ON pc.oid = pt.relid
                                      WHERE pt.isleaf)
                                ELSE c.reltuples::bigint END AS row_count,
                           CASE WHEN c.relkind = 'p'
                                THEN (SELECT COALESCE(sum(pg_table_size(pt.relid)), 0)::bigint
                                      FROM pg_partition_tree(c.oid) pt WHERE pt.isleaf)
                                ELSE pg_table_size(c.oid) END AS data_bytes,
                           CASE WHEN c.relkind = 'p'
                                THEN (SELECT COALESCE(sum(pg_indexes_size(pt.relid)), 0)::bigint
                                      FROM pg_partition_tree(c.oid) pt WHERE pt.isleaf)
                                ELSE pg_indexes_size(c.oid) END AS index_bytes
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = ?
                      AND c.relkind IN ('r', 'p')
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    LIMIT 1
                    """;
            BasicStats stats = jdbc().query(statsSql, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new BasicStats(
                        rs.getLong("row_count"),
                        rs.getLong("data_bytes"),
                        rs.getLong("index_bytes"));
            }, tableName);
            if (stats == null) {
                return TableDetail.unsupported(tableName, "테이블을 찾을 수 없습니다: " + tableName);
            }
            // reltuples가 0 이하(미ANALYZE 등)면 나눗셈 불가 → -1(미확보)로 정직 표기
            long avgRowBytes = stats.rowCount() > 0 ? stats.dataBytes() / stats.rowCount() : -1;

            // 인덱스 — indkey를 unnest WITH ORDINALITY로 펼쳐 컬럼 순서를 보존(describeSchema와 같은 패턴).
            // 카디널리티 재료: 선두 컬럼(indkey[0], int2vector는 0-베이스)의 pg_stats.n_distinct와 reltuples.
            // pg_stats가 없으면(ANALYZE 전) LEFT JOIN으로 n_distinct는 NULL → 카디널리티 미확보.
            String indexSql = """
                    SELECT i.relname AS index_name,
                           ix.indisunique AS is_unique,
                           am.amname AS index_type,
                           a.attname AS column_name,
                           st.n_distinct::float8 AS n_distinct,
                           c.reltuples::float8 AS reltuples
                    FROM pg_index ix
                    JOIN pg_class c ON c.oid = ix.indrelid
                    JOIN pg_class i ON i.oid = ix.indexrelid
                    JOIN pg_am am ON am.oid = i.relam
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
                    LEFT JOIN pg_stats st ON st.schemaname = n.nspname
                          AND st.tablename = c.relname
                          AND st.attname = (SELECT a2.attname FROM pg_attribute a2
                                            WHERE a2.attrelid = c.oid AND a2.attnum = ix.indkey[0])
                    WHERE c.relname = ?
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY i.relname, k.ord
                    """;
            List<IndexRow> indexRows = jdbc().query(indexSql,
                    (rs, i) -> new IndexRow(
                            rs.getString("index_name"),
                            rs.getBoolean("is_unique"),
                            rs.getString("index_type"),
                            rs.getString("column_name"),
                            rs.getObject("n_distinct", Double.class),
                            rs.getDouble("reltuples")),
                    tableName);
            List<TableDetail.IndexDetail> indexes = groupIndexes(indexRows);

            // DDL 재구성 재료 — 컬럼(information_schema), PK(pg_constraint contype='p'), 인덱스 정의(pg_indexes)
            List<TableDetailSupport.ColumnDef> columns = jdbc().query("""
                    SELECT column_name, data_type,
                           character_maximum_length, numeric_precision, numeric_scale,
                           is_nullable, column_default
                    FROM information_schema.columns
                    WHERE table_name = ?
                      AND table_schema NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY ordinal_position
                    """,
                    (rs, i) -> new TableDetailSupport.ColumnDef(
                            rs.getString("column_name"),
                            formatType(rs.getString("data_type"),
                                    rs.getObject("character_maximum_length", Integer.class),
                                    rs.getObject("numeric_precision", Integer.class),
                                    rs.getObject("numeric_scale", Integer.class)),
                            "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                            rs.getString("column_default")),
                    tableName);
            List<String> pkColumns = jdbc().query("""
                    SELECT a.attname
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
                    WHERE c.relname = ?
                      AND con.contype = 'p'
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY k.ord
                    """,
                    (rs, i) -> rs.getString("attname"),
                    tableName);
            // FK·CHECK는 PostgreSQL 자체 함수 pg_get_constraintdef로 정확히 얻는다(pg_dump가 쓰는 것과 같은 함수).
            // conname은 quote_ident로 안전하게 감싸므로 식별자 주입 여지가 없다 → "근사"가 아니라 정확한 재구성.
            List<String> tableConstraints = jdbc().query("""
                    SELECT 'CONSTRAINT ' || quote_ident(con.conname) || ' ' || pg_get_constraintdef(con.oid) AS clause
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = ?
                      AND con.contype IN ('f', 'c')
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY con.contype DESC, con.conname
                    """,
                    (rs, i) -> rs.getString("clause"),
                    tableName);
            // pg_indexes.indexdef는 이미 완성된 CREATE INDEX 텍스트라 그대로 재료로 넘긴다
            // (단, PK/UNIQUE 제약이 만든 인덱스는 tableConstraints에서 이미 다뤄지지 않는 순수 인덱스만 남긴다는 뜻은 아니고,
            //  pg_indexes는 제약이 만든 인덱스도 포함하므로 PK는 위에서 별도 PRIMARY KEY 절로, 인덱스는 CREATE INDEX로 이중 표기될 수 있다)
            List<String> indexDefs = jdbc().query("""
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE tablename = ?
                      AND schemaname NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY indexname
                    """,
                    (rs, i) -> rs.getString("indexdef"),
                    tableName);
            String ddl = TableDetailSupport.reconstructDdl(tableName, columns, pkColumns, tableConstraints, indexDefs);

            // 재구성에 담지 못하는 것은 트리거·파티션 정의뿐 — 실제로 있을 때만 정직히 밝힌다(없으면 사실상 완전한 재현)
            Integer triggerCount = jdbc().queryForObject("""
                    SELECT count(*)::int FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = ? AND NOT t.tgisinternal
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    """, Integer.class, tableName);
            int triggers = triggerCount != null ? triggerCount : 0;
            List<Boolean> partRows = jdbc().query("""
                    SELECT (c.relkind = 'p') AS p FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = ? AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    LIMIT 1
                    """, (rs, i) -> rs.getBoolean("p"), tableName);
            boolean partitioned = !partRows.isEmpty() && Boolean.TRUE.equals(partRows.get(0));

            StringBuilder note = new StringBuilder(
                    "PostgreSQL은 테이블별 스토리지 엔진·생성 시각 개념이 없어 engine/createdAt은 미제공. "
                    + "카디널리티는 선두 컬럼 n_distinct 추정. "
                    + "DDL은 단일 CREATE 명령이 없어 PostgreSQL 자체 정의 함수(pg_get_constraintdef·pg_get_indexdef)로 "
                    + "컬럼·PK·FK·CHECK·인덱스까지 재구성.");
            if (triggers > 0 || partitioned) {
                note.append(" 단, 이 테이블의");
                if (triggers > 0) {
                    note.append(" 트리거 ").append(triggers).append("개");
                }
                if (partitioned) {
                    note.append(triggers > 0 ? "와" : "").append(" 파티션");
                }
                note.append(" 정의는 DDL에 포함하지 않음.");
            }
            if (partitioned) {
                note.append(" 파티션 테이블 — 행수·데이터·인덱스 크기는 리프 파티션 합산.");
            }
            return new TableDetail(tableName, null, stats.rowCount(), stats.dataBytes(), stats.indexBytes(),
                    avgRowBytes, null, ddl, DdlSource.RECONSTRUCTED, indexes, note.toString());
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 테이블 상세 조회 실패: " + e.getMessage(), e);
        }
    }

    /** tableDetail 기본 통계 한 행 — reltuples 기반 추정 행수와 데이터/인덱스 바이트. */
    private record BasicStats(long rowCount, long dataBytes, long indexBytes) {
    }

    /** unnest로 펼친 인덱스-컬럼 한 행(인덱스별 여러 행으로 나옴). n_distinct/reltuples는 인덱스당 동일. */
    private record IndexRow(String indexName, boolean unique, String type, String column,
                            Double nDistinct, double reltuples) {
    }

    /** 인덱스-컬럼 행들을 인덱스 단위로 묶는다(컬럼 순서 보존, 카디널리티는 선두 컬럼 통계로 인덱스당 1회 계산). */
    private static List<TableDetail.IndexDetail> groupIndexes(List<IndexRow> rows) {
        LinkedHashMap<String, IndexAcc> byIndex = new LinkedHashMap<>();
        for (IndexRow r : rows) {
            IndexAcc acc = byIndex.computeIfAbsent(r.indexName(),
                    k -> new IndexAcc(r.unique(), r.type(), r.nDistinct(), r.reltuples()));
            acc.columns.add(r.column());
        }
        List<TableDetail.IndexDetail> out = new ArrayList<>();
        for (Map.Entry<String, IndexAcc> e : byIndex.entrySet()) {
            IndexAcc a = e.getValue();
            out.add(new TableDetail.IndexDetail(e.getKey(), a.columns, a.unique, a.type,
                    estimateCardinality(a.nDistinct, a.reltuples)));
        }
        return out;
    }

    /** 인덱스 하나의 누적 상자 — 컬럼 순서 목록과 카디널리티 추정 재료(선두 컬럼 n_distinct·reltuples). */
    private static final class IndexAcc {
        final boolean unique;
        final String type;
        final Double nDistinct;
        final double reltuples;
        final List<String> columns = new ArrayList<>();

        IndexAcc(boolean unique, String type, Double nDistinct, double reltuples) {
            this.unique = unique;
            this.type = type;
            this.nDistinct = nDistinct;
            this.reltuples = reltuples;
        }
    }

    /**
     * 선두 컬럼 n_distinct로 인덱스 카디널리티를 추정한다 — PostgreSQL은 인덱스별 네이티브 카디널리티가 없다.
     * n_distinct 의미론(pg_stats): 0 이상이면 절대 고유값 수, 음수면 전체 행수 대비 <b>음의 비율</b>이라
     * (-n_distinct × reltuples)로 환산한다. pg_stats 행이 없으면(ANALYZE 전) NULL → 미확보로 정직히 null.
     */
    private static Long estimateCardinality(Double nDistinct, double reltuples) {
        if (nDistinct == null) {
            return null;
        }
        if (nDistinct >= 0) {
            return Math.round(nDistinct);
        }
        return Math.round(-nDistinct * reltuples);
    }

    /**
     * information_schema 타입명에 길이/정밀도를 붙여 재구성 DDL 가독성을 높인다(근사라 완벽할 필요는 없다).
     * 문자형은 (length), numeric/decimal은 (precision[,scale])만 다룬다 — 나머지는 data_type 원문 그대로.
     */
    private static String formatType(String dataType, Integer charLen, Integer numPrecision, Integer numScale) {
        if (charLen != null && charLen > 0) {
            return dataType + "(" + charLen + ")";
        }
        if (numPrecision != null && ("numeric".equals(dataType) || "decimal".equals(dataType))) {
            return (numScale != null && numScale > 0)
                    ? dataType + "(" + numPrecision + ", " + numScale + ")"
                    : dataType + "(" + numPrecision + ")";
        }
        return dataType;
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        try {
            return jdbc().query("EXPLAIN (FORMAT JSON) " + sql, rs -> {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1));
                }
                return sb.toString();
            });
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL EXPLAIN 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 플랜 변경 감지용 — pg_stat_statements의 정규화 텍스트($1·$2 플레이스홀더)를 그대로 계획으로.
     * PostgreSQL 16의 GENERIC_PLAN이 정확히 이 용도다: 파라미터 값 없이 제네릭 플랜을 산출한다.
     *
     * 풀을 안 쓰고 1회용 simple-protocol 커넥션을 여는 이유(실측으로 밟은 함정): pgjdbc는 기본
     * extended protocol이라 서버가 텍스트 속 $1을 <b>바인드 파라미터로 파싱</b>해 "0개 바인드" 에러가
     * 난다 — psql(simple)에서는 되던 게 JDBC에서 깨진다. GENERIC_PLAN은 simple protocol이 필요하고,
     * 이 호출은 회귀 감지 때만 드물게 일어나므로 1회용 커넥션 비용을 수용한다.
     */
    @Override
    public String explainNormalized(String sql) {
        requireSelect(sql);
        String url = jdbcUrl() + "&preferQueryMode=simple";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                url, instance.getUsername(), instance.getPassword());
             java.sql.Statement st = conn.createStatement()) {
            st.setQueryTimeout(10);
            StringBuilder sb = new StringBuilder();
            try (java.sql.ResultSet rs = st.executeQuery("EXPLAIN (GENERIC_PLAN, FORMAT JSON) " + sql)) {
                while (rs.next()) {
                    sb.append(rs.getString(1));
                }
            }
            return sb.toString();
        } catch (java.sql.SQLException e) {
            throw new OperatorException("PostgreSQL EXPLAIN(GENERIC_PLAN) 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 플랜 변경 감지용 shape — GENERIC_PLAN(플레이스홀더 채로)의 계획을 PlanShapes로 정규화한다.
     * SELECT가 아니거나 계획 획득 실패면 empty(회귀 알림은 계속되므로 조용히 스킵).
     */
    @Override
    public Optional<String> planShapeForDigest(String queryId, String queryText) {
        if (queryText == null || !queryText.trim().toLowerCase().startsWith("select")) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    PlanShapes.fromPgJson(explainNormalized(queryText)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 복제 슬롯 잔량 (C-1) — 비활성 슬롯이 WAL을 무한 보존해 디스크를 채우는 사고를 본다.
     * retained는 restart_lsn 이후 보존 중인 WAL 바이트, safe_wal_size는 max_slot_wal_keep_size까지
     * 남은 여유(무제한 설정이면 NULL). 스탠바이/슬롯 없음이면 빈 결과. 읽기 전용(pg_read_all_stats로 충분).
     */
    @Override
    public List<ReplicationSlot> replicationSlots() {
        String sql = """
                SELECT slot_name, active, wal_status,
                       pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS retained_bytes,
                       safe_wal_size
                FROM pg_replication_slots
                WHERE restart_lsn IS NOT NULL
                ORDER BY retained_bytes DESC
                """;
        try {
            return jdbc().query(sql, (rs, i) -> {
                long safe = rs.getLong("safe_wal_size");
                return new ReplicationSlot(
                        rs.getString("slot_name"),
                        rs.getBoolean("active"),
                        rs.getString("wal_status"),
                        rs.getLong("retained_bytes"),
                        rs.wasNull() ? null : safe);
            });
        } catch (DataAccessException e) {
            // 스탠바이(pg_current_wal_lsn 불가)·구버전 등은 사각을 못 볼 뿐 폴러를 죽이지 않는다
            return List.of();
        }
    }

    /**
     * 테이블 블로트/VACUUM 신호 (C-2) — pg_stat_user_tables의 죽은 튜플·마지막 autovacuum·
     * ANALYZE 이후 변경 수. dead 튜플 많은 순 상위 N개. 추정치 기반(n_dead_tup은 통계 추정). 읽기 전용.
     */
    @Override
    public List<TableBloat> tableBloat(int limit) {
        String sql = """
                SELECT schemaname || '.' || relname AS table_name,
                       n_dead_tup, n_live_tup,
                       CASE WHEN n_live_tup + n_dead_tup > 0
                            THEN n_dead_tup::float8 / (n_live_tup + n_dead_tup) ELSE 0 END AS dead_ratio,
                       last_autovacuum, n_mod_since_analyze
                FROM pg_stat_user_tables
                ORDER BY n_dead_tup DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql, (rs, i) -> {
                var ts = rs.getTimestamp("last_autovacuum");
                return new TableBloat(
                        rs.getString("table_name"),
                        rs.getLong("n_dead_tup"),
                        rs.getLong("n_live_tup"),
                        Math.round(rs.getDouble("dead_ratio") * 10000.0) / 10000.0,
                        ts == null ? null : ts.toLocalDateTime().toString(),
                        rs.getLong("n_mod_since_analyze"));
            }, limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 블로트 신호 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 누적 데드락 카운터 (3차 아크 D-3 → C-3 클러스터 집계) — PG는 개별 데드락 리포트를 안 남기고
     * pg_stat_database.deadlocks 누적 카운터만 준다(개별 사건은 로그에만, 뷰엔 없음). 폴 사이 델타
     * 판단은 OpsAlert가 한다. pg_read_all_stats로 충분. 카운터가 없으면(권한/버전) empty.
     *
     * C-3: 예전엔 current_database()만 봐서 같은 클러스터의 형제 DB에서 난 데드락을 놓쳤다. 이제
     * 템플릿(template0/template1)과 datname NULL(집계 행)을 뺀 클러스터 전체 deadlocks를 SUM한다.
     * 트레이드오프: 어느 DB에서 난 데드락인지 per-DB 귀속은 상실한다. 대신 클러스터의 어느 DB에서
     * 나든 하나도 놓치지 않는다 — 관제의 목적(놓침 방지)에 부합하는 선택. SUM은 대상 행이 항상 있어
     * 정상 경로에서 NULL이 아니지만, 방어적으로 Optional.ofNullable로 감싼다.
     */
    @Override
    public Optional<Long> deadlockCount() {
        try {
            Long n = jdbc().queryForObject(
                    "SELECT SUM(deadlocks) FROM pg_stat_database "
                            + "WHERE datname NOT IN ('template0', 'template1') AND datname IS NOT NULL",
                    Long.class);
            return Optional.ofNullable(n);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 실제 실행 계획 (D9) — EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON). 쿼리를 진짜 실행해
     * Plan Rows(추정) vs Actual Rows(실측)·Rows Removed by Filter·버퍼 히트를 준다.
     * Actual Rows·시간은 loops당 평균(공식 문서 명시) — 총량 환산(loops 곱)은 DeepAnalyzer가 한다.
     *
     * 안전: 트랜잭션을 열어 SET LOCAL statement_timeout으로 실행을 상한하고, 진단이 대상에 흔적을
     * 남기지 않도록 마지막에 롤백한다(ANALYZE는 SELECT라 롤백해도 데이터 변화는 없지만 원칙적 정리).
     * 한 커넥션에서 SET LOCAL → EXPLAIN을 이어야 하므로 ConnectionCallback을 쓴다.
     */
    @Override
    public String explainAnalyze(String sql) {
        requireSelect(sql);
        try {
            return jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<String>) conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (java.sql.Statement st = conn.createStatement()) {
                    // SET LOCAL은 트랜잭션 범위에서만 유효 — 진단이 끝나면 자동 소멸한다
                    st.execute("SET LOCAL statement_timeout = " + DEEP_DIAGNOSIS_TIMEOUT_MS);
                    StringBuilder sb = new StringBuilder();
                    try (java.sql.ResultSet rs = st.executeQuery(
                            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
                        while (rs.next()) {
                            sb.append(rs.getString(1));
                        }
                    }
                    return sb.toString();
                } finally {
                    conn.rollback();
                    conn.setAutoCommit(autoCommit);
                }
            });
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL EXPLAIN ANALYZE 실패(타임아웃/권한 확인): "
                    + e.getMessage(), e);
        }
    }

    /** 후보 인덱스 형식: table(col1, col2) — 식별자만 허용(인젝션 방어). 그룹1=테이블, 그룹2=컬럼목록 */
    private static final java.util.regex.Pattern CANDIDATE = java.util.regex.Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)\\s*\\)\\s*$");

    /** EXPLAIN JSON 파싱용. ObjectMapper는 스레드 안전해 정적 공유해도 된다. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 이 비율 미만으로 비용이 떨어져야 ADVISED — 부동소수 노이즈를 걸러내는 "유의미한 감소" 문턱(1% 이상). */
    private static final double COST_IMPROVEMENT_THRESHOLD = 0.99;

    /**
     * 인덱스 어드바이저 (B3) — HypoPG로 진짜 가상 인덱스를 만들어 플랜 비용을 비교한다.
     * 대상 DB에는 실제 인덱스를 만들지 않는다(가상 인덱스는 세션 로컬 메모리에만 존재) — 이게 이 기능의
     * 핵심 가치다. hypopg_create_index → 같은 커넥션에서 EXPLAIN 재실행 → hypopg_reset()까지 반드시
     * 하나의 Connection에서 해야 한다(다른 커넥션은 그 가상 인덱스를 못 본다) — 그래서 ConnectionCallback.
     *
     * columns가 비면 UNSUPPORTED(자동 컬럼 추천은 범위 밖). HypoPG 확장이 없으면 UNSUPPORTED("HypoPG
     * 확장 필요") — 통과로 위장하지 않는다. columns는 식별자 화이트리스트로만 파싱해 인젝션을 막는다.
     */
    @Override
    public IndexAdvice adviseIndex(String sql, String columns) {
        requireSelect(sql);
        if (columns == null || columns.isBlank()) {
            return IndexAdvice.unsupported(
                    "후보 인덱스(columns) 미지정 — 자동 컬럼 추천은 범위 밖입니다. 예: users(category)");
        }
        String ddl = buildCreateIndexDdl(columns); // 형식/식별자 위반이면 IllegalArgumentException(400)
        try {
            return jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<IndexAdvice>) conn -> {
                // HypoPG 확장 확보 — 없거나 설치 권한이 없으면 UNSUPPORTED로 정직하게 내려간다
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE EXTENSION IF NOT EXISTS hypopg");
                } catch (java.sql.SQLException e) {
                    return IndexAdvice.unsupported(
                            "HypoPG 확장 필요 — CREATE EXTENSION hypopg 실패(미설치/권한 없음): " + e.getMessage());
                }
                String beforePlan = explainJson(conn, sql);
                double beforeCost = totalCost(beforePlan);
                // 가상 인덱스 생성은 파라미터 바인딩(?)으로 — ddl은 이미 검증됐지만 이중 방어
                String afterPlan;
                try (java.sql.PreparedStatement ps =
                             conn.prepareStatement("SELECT hypopg_create_index(?)")) {
                    ps.setString(1, ddl);
                    ps.execute();
                    afterPlan = explainJson(conn, sql);
                } finally {
                    // 가상 인덱스는 세션 로컬이라 커넥션 반납만으로도 사라지지만, 풀 재사용을 감안해 명시적으로 정리
                    try (java.sql.Statement st = conn.createStatement()) {
                        st.execute("SELECT hypopg_reset()");
                    }
                }
                double afterCost = totalCost(afterPlan);
                double reductionPct = beforeCost <= 0 ? 0 : (beforeCost - afterCost) / beforeCost * 100.0;
                if (afterCost < beforeCost * COST_IMPROVEMENT_THRESHOLD) {
                    return IndexAdvice.advised(
                            "가상 인덱스로 Total Cost %.2f → %.2f (%.1f%% 감소) — 옵티마이저가 이 인덱스를 채택했습니다"
                                    .formatted(beforeCost, afterCost, reductionPct),
                            ddl, beforePlan, afterPlan, beforeCost, afterCost);
                }
                return IndexAdvice.noBenefit(
                        "가상 인덱스를 만들어도 Total Cost %.2f → %.2f (유의미한 감소 없음) — 옵티마이저가 채택하지 않았습니다"
                                .formatted(beforeCost, afterCost),
                        ddl, beforePlan, afterPlan, beforeCost, afterCost);
            });
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 인덱스 어드바이저 실패: " + e.getMessage(), e);
        }
    }

    /** 같은 커넥션에서 EXPLAIN (FORMAT JSON)을 돌려 플랜 JSON 문자열을 얻는다(가상 인덱스가 보이도록). */
    private static String explainJson(java.sql.Connection conn, String sql) throws java.sql.SQLException {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1));
            }
            return sb.toString();
        }
    }

    /** EXPLAIN JSON의 최상위 플랜 Total Cost를 뽑는다. 구조: [{"Plan": {"Total Cost": n, ...}}] */
    private static double totalCost(String planJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode plan = MAPPER.readTree(planJson).get(0).get("Plan");
            return plan.get("Total Cost").asDouble();
        } catch (Exception e) {
            throw new OperatorException("실행계획에서 Total Cost 파싱 실패: " + e.getMessage(), e);
        }
    }

    /**
     * "table(col1, col2)"을 검증된 CREATE INDEX DDL로 변환한다. 테이블·컬럼은 식별자 화이트리스트
     * (영문/숫자/밑줄, 첫 글자는 문자·밑줄)만 통과시켜, 사용자 입력이 DDL에 그대로 실려도 인젝션이 불가능하다.
     */
    static String buildCreateIndexDdl(String columns) {
        java.util.regex.Matcher m = CANDIDATE.matcher(columns);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "후보 인덱스 형식이 올바르지 않습니다 — table(col1,col2) 형태의 식별자여야 합니다: " + columns);
        }
        String table = m.group(1);
        // 컬럼 목록의 공백을 정규화해 깔끔한 DDL로 재구성 (조각은 모두 식별자 검증을 통과한 값)
        String cols = java.util.Arrays.stream(m.group(2).split(","))
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
        return "CREATE INDEX ON " + table + " (" + cols + ")";
    }

    /**
     * 대기 이벤트 — pg_stat_activity의 활성 세션 스냅샷 집계. 다른 기종의 누적 카운터와 달리
     * "지금 이 순간" 각 세션이 무엇을 기다리는지다 (count=세션 수, totalMs=0 — 시간 누적 없음).
     *
     * 한계: 순간 스냅샷이라 짧은 대기는 잡히지 않는다. 누적 대기 통계를 원하면 pg_wait_sampling
     * 확장이 정석인데 stock 이미지에는 없다 — 확장 경로는 shared_preload_libraries에
     * pg_wait_sampling을 추가하고 pg_wait_sampling_profile을 읽는 것. (여기서는 설치를 강제하지
     * 않고 어떤 환경에서도 동작하는 스냅샷 방식을 쓴다)
     *
     * wait_event IS NULL인 활성 세션은 대기가 아니라 실제로 실행 중 — "CPU"로 표기한다.
     */
    @Override
    public List<WaitEvent> waitEvents(int limit) {
        // 자기 자신(이 조회 세션)은 항상 active라 노이즈 — pg_backend_pid()로 제외
        String sql = """
                SELECT COALESCE(wait_event, 'CPU (대기 아님)') AS event,
                       COALESCE(wait_event_type, 'CPU') AS category,
                       COUNT(*) AS sessions
                FROM pg_stat_activity
                WHERE state = 'active'
                  AND pid <> pg_backend_pid()
                GROUP BY 1, 2
                ORDER BY sessions DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new WaitEvent(
                            rs.getString("event"),
                            rs.getString("category"),
                            rs.getLong("sessions"),
                            0), // 스냅샷 방식이라 대기시간 누적이 없다
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 활성 세션 + 블로킹 관계 — pg_stat_activity에서 실제로 일하거나 락에 막힌 세션만 추린다.
     * blockedByPid는 pg_blocking_pids(pid)의 첫 값(배열이 비면 NULL). 이 함수는 "누가 나를 막나"를
     * 잠금 그래프에서 직접 계산해 줘서, 우리가 락 뷰를 조인할 필요가 없다.
     *
     * idle(트랜잭션 밖에서 노는) 커넥션은 제외 — 노이즈이고 블로킹과 무관하다. 단 'idle in
     * transaction'은 락을 쥔 채 남 여럿을 막을 수 있어 남긴다(state <> 'idle'). 자기 조회 세션은
     * pg_backend_pid()로 제외한다.
     */
    @Override
    public List<SessionInfo> activeSessions(int limit) {
        String sql = """
                SELECT pid, usename, state, wait_event,
                       (pg_blocking_pids(pid))[1] AS blocked_by,
                       query,
                       COALESCE(EXTRACT(EPOCH FROM (now() - query_start)) * 1000, 0) AS elapsed_ms
                FROM pg_stat_activity
                WHERE backend_type = 'client backend'
                  AND pid <> pg_backend_pid()
                  AND state IS DISTINCT FROM 'idle'
                ORDER BY elapsed_ms DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> {
                        // wasNull()은 반드시 getLong 직후에 읽는다 — 다른 컬럼을 먼저 읽으면
                        // wasNull()이 그 컬럼 기준이 되어 blocked_by의 null이 0으로 새어 나온다.
                        long blockedBy = rs.getLong("blocked_by");
                        Long blockedByOrNull = rs.wasNull() ? null : blockedBy;
                        return new SessionInfo(
                                rs.getLong("pid"),
                                rs.getString("usename"),
                                rs.getString("state"),
                                rs.getString("wait_event"),
                                blockedByOrNull,
                                rs.getString("query"),
                                rs.getDouble("elapsed_ms"));
                    },
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 세션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 세션 종료 — force면 pg_terminate_backend(백엔드 강제 종료), 아니면 pg_cancel_backend(실행 문장만 취소).
     * pid는 파라미터로 바인딩(숫자)해 인젝션 여지가 없다. 함수명은 사용자 입력이 아니라 고정 2택이다.
     * WHERE ? <> pg_backend_pid()로 자기 수집 커넥션은 종료를 거부한다 — 관제 도구가 제 발을 쏘지 않게.
     */
    @Override
    public String killSession(long pid, boolean force) {
        String fn = force ? "pg_terminate_backend" : "pg_cancel_backend";
        try {
            // pid는 int4 컬럼이라 (?)::int로 캐스팅한다 — Java long은 bigint로 바인딩돼
            // pg_cancel_backend(bigint) 같은 없는 시그니처가 되어 "bad grammar"로 실패한다.
            Boolean ok = jdbc().query(
                    "SELECT " + fn + "((?)::int) AS ok WHERE (?)::int <> pg_backend_pid()",
                    rs -> rs.next() ? (Boolean) rs.getObject("ok") : null,
                    pid, pid);
            if (ok == null) {
                throw new OperatorException("자기 수집 커넥션은 종료할 수 없습니다 (pid=" + pid + ")");
            }
            return "%s(pid=%d) → %s".formatted(fn, pid, ok);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 세션 종료 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스키마 구조 — 컬럼은 information_schema.columns(권한 있는 테이블만 보임), 인덱스는 pg_index를
     * unnest해 컬럼 순서(indkey 순)와 유니크(indisunique)를 정확히 뽑는다. pg_indexes의 indexdef는
     * DDL 텍스트라 컬럼 리스트·유니크를 파싱해야 해서, 대신 카탈로그를 직접 조인했다.
     * 시스템 스키마(pg_catalog, information_schema)는 제외. 표현식 인덱스의 attnum=0 요소는
     * pg_attribute 조인에서 빠진다(컬럼으로 환원 불가) — diff 요약에서는 그 부분만 비는 것을 허용한다.
     * relkind는 일반('r')과 파티션 부모('p')를 함께 잡는다 — 'r'만 잡으면 파티션 부모의 인덱스가
     * 통째로 빠져 "idx: (없음)"으로 오판된다(문의 첨부에서 실제로 겪은 회귀).
     */
    @Override
    public SchemaSnapshot describeSchema() {
        String columnsSql = """
                SELECT table_name, column_name, data_type, is_nullable, ordinal_position
                FROM information_schema.columns
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY table_name, ordinal_position
                """;
        String indexesSql = """
                SELECT t.relname AS table_name, i.relname AS index_name,
                       a.attname AS column_name, ix.indisunique AS is_unique
                FROM pg_index ix
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND t.relkind IN ('r', 'p')
                ORDER BY t.relname, i.relname, k.ord
                """;
        try {
            List<SchemaSupport.ColumnRow> columns = jdbc().query(columnsSql,
                    (rs, i) -> new SchemaSupport.ColumnRow(
                            rs.getString("table_name"),
                            new ColumnSchema(rs.getString("column_name"), rs.getString("data_type"),
                                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                                    rs.getInt("ordinal_position"))));
            List<SchemaSupport.IndexColumnRow> indexes = jdbc().query(indexesSql,
                    (rs, i) -> new SchemaSupport.IndexColumnRow(
                            rs.getString("table_name"), rs.getString("index_name"),
                            rs.getString("column_name"), rs.getBoolean("is_unique")));
            return SchemaSupport.build(instance.getType().name(), instance.getDbName(),
                    columns, indexes, SchemaSupport.DEFAULT_MAX_TABLES);
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 스키마 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 파라미터 — pg_settings가 이름·값·단위를 함께 준다(단위는 8kB/ms 등, 없으면 null) */
    @Override
    public List<DbParameter> parameters() {
        try {
            return jdbc().query("SELECT name, setting, unit FROM pg_settings ORDER BY name",
                    (rs, i) -> ParameterSupport.of(rs.getString("name"),
                            rs.getString("setting"), rs.getString("unit")));
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 파라미터 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 복제 상태 — pg_is_in_recovery()면 레플리카(재생 지연 = now - 마지막 재생 시각),
     * 아니면 pg_stat_replication의 연결 수로 프라이머리/단독을 구분한다.
     */
    @Override
    public ReplicationState replicationState() {
        // 두 조회는 세션 지역 상태를 공유하지 않아 각각 실행해도 결과가 같다
        try {
            ReplicationState asReplica = jdbc().query(
                    "SELECT pg_is_in_recovery() AS in_recovery, " +
                    "COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())), 0) AS lag_sec",
                    rs -> {
                        rs.next();
                        return rs.getBoolean("in_recovery")
                                ? new ReplicationState("REPLICA", rs.getDouble("lag_sec"), "recovery 모드")
                                : null;
                    });
            if (asReplica != null) {
                return asReplica;
            }
            Integer replicas = jdbc().queryForObject(
                    "SELECT COUNT(*) AS replicas FROM pg_stat_replication", Integer.class);
            return replicas != null && replicas > 0
                    ? new ReplicationState("PRIMARY", 0, "replicas=" + replicas)
                    : new ReplicationState("STANDALONE", 0, "복제 구성 없음");
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 통계 수집 건강 실측 (심화 아크 5) — PG는 digest 포화 소실이 없는 대신 pg_stat_statements.max
     * 초과 시 덜 쓰인 쿼리를 evict한다. dealloc(누적 evict 횟수)이 증가 중이면 저빈도 쿼리의
     * 시점 비교·베이스라인 신뢰도가 떨어진다. pg_stat_statements_info는 PG13+ — 없으면 -1(위장 금지).
     * PS 사각(ps*)은 PG에 해당 없음(-1): 정규화가 파스 트리 기반이라 PS도 동일 queryid로 집계된다.
     */
    @Override
    public StatsHealth statsHealth() {
        try {
            Long rows = jdbc().queryForObject("SELECT COUNT(*) FROM pg_stat_statements", Long.class);
            Long max = jdbc().queryForObject(
                    "SELECT setting::bigint FROM pg_settings WHERE name = 'pg_stat_statements.max'", Long.class);
            long dealloc;
            try {
                Long d = jdbc().queryForObject("SELECT dealloc FROM pg_stat_statements_info", Long.class);
                dealloc = d == null ? -1 : d;
            } catch (DataAccessException e) {
                dealloc = -1;   // PG12 이하 — 뷰 자체가 없어 미확보로 정직 표기
            }
            return new StatsHealth(
                    rows == null ? -1 : rows,
                    max == null ? -1 : max,
                    dealloc,
                    -1, -1,
                    true,
                    "stats=pg_stat_statements, evict=pg_stat_statements_info.dealloc(누적)");
        } catch (DataAccessException e) {
            throw new OperatorException("PostgreSQL 통계 수집 건강 조회 실패: " + e.getMessage(), e);
        }
    }
}
