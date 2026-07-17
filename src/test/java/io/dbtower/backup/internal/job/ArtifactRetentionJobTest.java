package io.dbtower.backup.internal.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 산출물 보존 판정 — 핵심 계약은 둘: 보존 일수를 지난 것만 지우고,
 * 같은 스트림의 최신 1개는 나이와 무관하게 남긴다(oplog ts 마커·체인 보충 장부 보호).
 */
class ArtifactRetentionJobTest {

    @TempDir
    Path dir;

    private Path file(String name, Instant mtime) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }

    @Test
    void 오래된_것만_지우되_스트림별_최신본은_남긴다() throws IOException {
        Instant now = Instant.now();
        Instant cutoff = now.minus(14, ChronoUnit.DAYS);
        // 같은 binlog 스트림: 오래된 2개 + 그 중 최신 1개(역시 오래됨) — 최신만 생존해야 함
        Path oldA = file("mysql-binlog-local-mysql-2026-06-01T10-00-00.000001-binlog.000001", now.minus(40, ChronoUnit.DAYS));
        Path oldB = file("mysql-binlog-local-mysql-2026-06-20T10-00-00.000001-binlog.000002", now.minus(20, ChronoUnit.DAYS));
        // 다른 스트림(oplog): 오래된 1개뿐 — 그룹 최신이라 생존
        Path oplog = file("mongo-oplog-local-mongo-2026-06-01T10-00-00.000001-ts123_1.archive", now.minus(40, ChronoUnit.DAYS));
        // 신선한 파일 — cutoff 안이라 생존
        Path fresh = file("mysql-local-mysql-2026-07-16T10-00-00.000001.sql", now.minus(1, ChronoUnit.DAYS));

        List<Path> deletable = ArtifactRetentionJob.selectDeletable(
                List.of(oldA, oldB, oplog, fresh), cutoff);

        assertThat(deletable).containsExactly(oldA); // oldB는 스트림 최신, oplog는 그룹 유일, fresh는 신선
    }

    @Test
    void 규약_밖_이름은_각자_최신_취급이라_지워지지_않는다() throws IOException {
        Instant now = Instant.now();
        Path odd = file("manual-copy.tar", now.minus(100, ChronoUnit.DAYS));
        List<Path> deletable = ArtifactRetentionJob.selectDeletable(
                List.of(odd), now.minus(14, ChronoUnit.DAYS));
        assertThat(deletable).isEmpty();
    }

    @Test
    void 그룹_키는_타임스탬프_앞까지다() {
        assertThat(ArtifactRetentionJob.groupKey(
                "mysql-binlog-local-mysql-2026-07-15T10-08-40.199757-binlog.000008"))
                .isEqualTo("mysql-binlog-local-mysql");
        assertThat(ArtifactRetentionJob.groupKey("meta-x.sql")).isEqualTo("meta-x.sql");
    }
}
