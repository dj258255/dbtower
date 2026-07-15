package io.dbtower.backup.internal.job;

import io.dbtower.backup.internal.RemoteBackupStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 플랫폼 메타 DB 자기 백업 (Phase 1) — "관제탑이 자기 자신도 지킨다".
 *
 * 지금까지 백업은 관리 "대상" DB만 떴다. 정작 사용자·모든 대상 자격증명·정책·이력을 담은 DBTower
 * 자신의 메타 DB(PostgreSQL)는 도커 볼륨 하나에만 의존했다 — 볼륨이 날아가면 플랫폼 상태가 전소한다.
 * 이 잡은 이미지에 번들된 pg_dump로 메타 DB를 스스로 덤프하고(로컬 {backup.dir}/meta/), 원격 보관이
 * 켜져 있으면 오프사이트(meta/ 네임스페이스)로도 올린다. 대상 백업 이력(BackupRun)과는 섞지 않는다.
 *
 * 비밀번호는 argv 금지 원칙에 따라 PGPASSWORD 환경변수로만 전달한다. pg_dump가 없거나 메타 DB가
 * PostgreSQL이 아니면(로컬 개발 등) 조용히 건너뛴다(기능 게이트) — 자기 백업 때문에 기동이 흔들리면 안 된다.
 */
@Component
public class MetaBackupJob {

    private static final Logger log = LoggerFactory.getLogger(MetaBackupJob.class);

    /** jdbc:postgresql://host:port/db?params — host/port/db 추출 */
    private static final Pattern PG_URL =
            Pattern.compile("^jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?;]+)");

    private final boolean enabled;
    private final int retentionCount;
    private final Path metaDir;
    private final String url;
    private final String username;
    private final String password;
    private final RemoteBackupStore remoteStore;

    public MetaBackupJob(@Value("${dbtower.meta-backup.enabled:true}") boolean enabled,
                         @Value("${dbtower.meta-backup.retention-count:7}") int retentionCount,
                         @Value("${dbtower.backup.dir:./backups}") String backupDir,
                         @Value("${spring.datasource.url}") String url,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password:}") String password,
                         RemoteBackupStore remoteStore) {
        this.enabled = enabled;
        this.retentionCount = Math.max(1, retentionCount);
        this.metaDir = Path.of(backupDir, "meta");
        this.url = url;
        this.username = username;
        this.password = password;
        this.remoteStore = remoteStore;
    }

    // lockAtMostFor 는 덤프가 클 수 있어 넉넉히, lockAtLeastFor 는 주기의 상당부분을 붙잡아 노드 중복 방지.
    @Scheduled(fixedDelayString = "${dbtower.meta-backup.interval-ms:86400000}")
    @SchedulerLock(name = "meta-backup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void run() {
        if (!enabled) {
            return;
        }
        Optional<PgTarget> target = parsePostgres(url);
        if (target.isEmpty()) {
            log.debug("메타 백업 건너뜀 — 메타 DB가 PostgreSQL이 아님(url={})", url);
            return;
        }
        try {
            Files.createDirectories(metaDir);
            Path out = metaDir.resolve("dbtower-meta-" + timestamp() + ".sql");
            int code = runPgDump(target.get(), out);
            if (code != 0) {
                log.warn("메타 백업 실패 — pg_dump exit={}", code);
                Files.deleteIfExists(out);
                return;
            }
            log.info("메타 백업 완료 {} ({} bytes)", out, Files.size(out));
            remoteStore.uploadTo("meta", out.toString());
            pruneOld(metaDir, retentionCount);
        } catch (IOException e) {
            log.warn("메타 백업 건너뜀 — pg_dump 실행 불가(로컬에 클라이언트 없음 등): {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int runPgDump(PgTarget t, Path out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump", "-h", t.host(), "-p", String.valueOf(t.port()),
                "-U", username, "-d", t.database(), "-f", out.toString());
        pb.environment().put("PGPASSWORD", password);  // argv 금지 — 비밀번호는 환경변수로만
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 출력을 흘려보내 파이프 버퍼가 차서 pg_dump가 멈추는 것을 막는다
        try (var in = p.getInputStream()) {
            in.readAllBytes();
        }
        return p.waitFor();
    }

    /** 최신 N개만 남기고 오래된 덤프를 삭제 — 메타 백업이 디스크를 무한 적재하지 않게 */
    static void pruneOld(Path dir, int keep) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dumps = files
                    .filter(f -> f.getFileName().toString().startsWith("dbtower-meta-"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
            for (int i = keep; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        }
    }

    /** jdbc:postgresql URL을 host/port/db로 분해 — PostgreSQL이 아니면 empty(기능 게이트) */
    static Optional<PgTarget> parsePostgres(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        Matcher m = PG_URL.matcher(jdbcUrl);
        if (!m.find()) {
            return Optional.empty();
        }
        String host = m.group(1);
        int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 5432;
        return Optional.of(new PgTarget(host, port, m.group(3)));
    }

    record PgTarget(String host, int port, String database) {
    }

    private static String timestamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }
}
