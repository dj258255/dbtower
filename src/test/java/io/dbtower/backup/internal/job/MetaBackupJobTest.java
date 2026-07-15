package io.dbtower.backup.internal.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 메타 백업의 순수 로직 — JDBC URL 파싱(기능 게이트)과 보존 정리(무한 적재 방지). */
class MetaBackupJobTest {

    @Test
    void postgres_url을_host_port_db로_분해한다() {
        Optional<MetaBackupJob.PgTarget> t =
                MetaBackupJob.parsePostgres("jdbc:postgresql://meta-db:5432/dbtower?reWriteBatchedInserts=true");
        assertThat(t).isPresent();
        assertThat(t.get().host()).isEqualTo("meta-db");
        assertThat(t.get().port()).isEqualTo(5432);
        assertThat(t.get().database()).isEqualTo("dbtower");
    }

    @Test
    void 포트_생략시_5432_기본() {
        Optional<MetaBackupJob.PgTarget> t = MetaBackupJob.parsePostgres("jdbc:postgresql://localhost/dbtower");
        assertThat(t).isPresent();
        assertThat(t.get().port()).isEqualTo(5432);
    }

    @Test
    void postgres가_아니면_empty_기능게이트() {
        // 메타 DB가 PG가 아닌(로컬 H2 등) 경우 자기 백업을 조용히 건너뛴다
        assertThat(MetaBackupJob.parsePostgres("jdbc:h2:mem:x")).isEmpty();
        assertThat(MetaBackupJob.parsePostgres("jdbc:mysql://h/db")).isEmpty();
        assertThat(MetaBackupJob.parsePostgres(null)).isEmpty();
    }

    @Test
    void 보존은_최신_N개만_남기고_삭제한다(@TempDir Path dir) throws IOException {
        // 파일명에 타임스탬프가 있어 이름 내림차순 = 최신 우선
        for (String ts : new String[]{"20260101-000000", "20260102-000000", "20260103-000000",
                "20260104-000000"}) {
            Files.writeString(dir.resolve("dbtower-meta-" + ts + ".sql"), "dump");
        }
        Files.writeString(dir.resolve("무관파일.txt"), "x");  // 대상 아님

        MetaBackupJob.pruneOld(dir, 2);

        try (var s = Files.list(dir)) {
            var names = s.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "dbtower-meta-20260103-000000.sql",  // 최신 2개 유지
                    "dbtower-meta-20260104-000000.sql",
                    "무관파일.txt");                       // 접두사 다른 파일은 건드리지 않음
        }
    }
}
