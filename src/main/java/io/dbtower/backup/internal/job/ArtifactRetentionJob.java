package io.dbtower.backup.internal.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 백업 산출물 로컬 보존 정리 — 정책 병행 스케줄(V23)로 산출물이 상시 쌓이게 되면서 필요해졌다.
 * ClusterControl류 백업 관리의 retention 축에 해당한다(없으면 디스크가 무한히 찬다).
 *
 * 규칙 둘:
 *  - 보존 일수(mtime 기준)를 지난 파일만 지운다. 이력(backup_run)은 사실 기록이라 지우지 않는다 —
 *    이력에 위치는 남되 파일은 만료될 수 있음을 이 잡의 존재가 계약으로 만든다.
 *  - 같은 스트림(파일명에서 타임스탬프 이후를 뗀 접두 그룹)의 최신 1개는 나이와 무관하게 남긴다.
 *    Mongo oplog 증분의 ts 마커와 로그 체인 보충의 "이미 수집됨" 장부가 전부 최신 산출물
 *    파일명이라, 최신본을 지우면 증분이 전체 재덤프로 퇴행하거나 보충 수집이 중복 수집을 한다.
 *
 * meta/ 하위(플랫폼 자기 백업)는 MetaBackupJob이 자체 보존(retention-count)을 갖고 있어 건드리지
 * 않는다. retention-days<=0 이면 기능 게이트(정리 안 함 — 외부 보존 체계가 있는 조직).
 */
@Component
public class ArtifactRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionJob.class);

    /** 산출물 이름의 타임스탬프 부분 — 이 앞까지가 스트림 그룹 키다(예: mysql-binlog-local-mysql). */
    private static final Pattern TIMESTAMP = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.\\d+");

    private final Path backupDir;
    private final int retentionDays;

    public ArtifactRetentionJob(@Value("${dbtower.backup.dir:./backups}") String backupDir,
                                @Value("${dbtower.backup.artifact-retention-days:14}") int retentionDays) {
        this.backupDir = Path.of(backupDir);
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${dbtower.backup.artifact-retention-sweep-ms:21600000}")
    @SchedulerLock(name = "backup-artifact-retention", lockAtMostFor = "PT10M")
    public void sweep() {
        if (retentionDays <= 0 || !Files.isDirectory(backupDir)) {
            return; // 기능 게이트 — 보존 정리를 끈 조직이거나 산출물이 아직 없다
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<Path> deletable = selectDeletable(listArtifacts(), cutoff);
        long freed = 0;
        int deleted = 0;
        for (Path p : deletable) {
            try {
                long size = Files.size(p);
                Files.deleteIfExists(p);
                freed += size;
                deleted++;
            } catch (IOException e) {
                log.warn("산출물 보존 정리 실패 file={} cause={}", p.getFileName(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("백업 산출물 보존 정리 — {}일 초과 {}건 삭제, {}MB 확보 (스트림별 최신본은 보존)",
                    retentionDays, deleted, freed / (1024 * 1024));
        }
    }

    private List<Path> listArtifacts() {
        try (Stream<Path> s = Files.list(backupDir)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("산출물 디렉터리 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 삭제 대상 선정(순수 판정) — cutoff보다 오래됐고, 자기 스트림 그룹의 최신 파일이 아닌 것.
     */
    static List<Path> selectDeletable(List<Path> files, Instant cutoff) {
        Map<String, Path> newestPerGroup = new HashMap<>();
        Map<Path, Instant> mtimes = new HashMap<>();
        for (Path p : files) {
            Instant mtime = mtimeOf(p);
            if (mtime == null) {
                continue;
            }
            mtimes.put(p, mtime);
            String group = groupKey(p.getFileName().toString());
            Path current = newestPerGroup.get(group);
            if (current == null || mtime.isAfter(mtimes.get(current))) {
                newestPerGroup.put(group, p);
            }
        }
        List<Path> result = new ArrayList<>();
        for (Path p : files) {
            Instant mtime = mtimes.get(p);
            if (mtime == null || !mtime.isBefore(cutoff)) {
                continue;
            }
            if (newestPerGroup.containsValue(p)) {
                continue; // 스트림별 최신본 — ts 마커·체인 장부 보존
            }
            result.add(p);
        }
        return result;
    }

    /** 파일명에서 타임스탬프 이후를 떼어 스트림 그룹 키를 만든다 — 규약 밖 이름은 파일명 전체가 키(각자 최신 취급). */
    static String groupKey(String filename) {
        Matcher m = TIMESTAMP.matcher(filename);
        if (m.find()) {
            return filename.substring(0, m.start());
        }
        return filename;
    }

    private static Instant mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
