package de.example.backupmonitor.s3;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LiquibaseAutoConfiguration.class)
@Testcontainers
class S3CheckResultRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private S3CheckResultRepository repository;

    // ── findLatestPassedForInstance ───────────────────────────────────────────

    @Test
    void findLatestPassedForInstance_onlyPassedEntry_returnsIt() {
        save("inst-1", "job-1", true, Instant.now());

        Optional<S3CheckResultEntity> result = repository.findLatestPassedForInstance("inst-1");

        assertThat(result).isPresent();
        assertThat(result.get().getBackupJobId()).isEqualTo("job-1");
    }

    @Test
    void findLatestPassedForInstance_mixedResults_returnsLatestPassed() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        Instant t3 = Instant.parse("2026-01-03T00:00:00Z");

        save("inst-1", "job-old-passed", true,  t1);
        save("inst-1", "job-newer-failed", false, t2);
        save("inst-1", "job-newest-passed", true, t3);

        Optional<S3CheckResultEntity> result = repository.findLatestPassedForInstance("inst-1");

        assertThat(result).isPresent();
        assertThat(result.get().getBackupJobId()).isEqualTo("job-newest-passed");
    }

    @Test
    void findLatestPassedForInstance_allFailed_returnsEmpty() {
        save("inst-1", "job-1", false, Instant.now());
        save("inst-1", "job-2", false, Instant.now().minusSeconds(60));

        assertThat(repository.findLatestPassedForInstance("inst-1")).isEmpty();
    }

    @Test
    void findLatestPassedForInstance_ignoresOtherInstances() {
        save("inst-1", "job-a", true, Instant.now());
        save("inst-2", "job-b", true, Instant.now());

        Optional<S3CheckResultEntity> result = repository.findLatestPassedForInstance("inst-1");

        assertThat(result).isPresent();
        assertThat(result.get().getBackupJobId()).isEqualTo("job-a");
    }

    // ── findLatestForInstance ─────────────────────────────────────────────────

    @Test
    void findLatestForInstance_returnsNewest_regardlessOfPassed() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");

        save("inst-1", "job-old-passed",   true,  t1);
        save("inst-1", "job-newest-failed", false, t2);

        Optional<S3CheckResultEntity> result = repository.findLatestForInstance("inst-1");

        assertThat(result).isPresent();
        assertThat(result.get().getBackupJobId()).isEqualTo("job-newest-failed");
        assertThat(result.get().isAllPassed()).isFalse();
    }

    @Test
    void findLatestForInstance_noEntries_returnsEmpty() {
        assertThat(repository.findLatestForInstance("inst-unknown")).isEmpty();
    }

    // ── deleteOldEntriesPerInstance ───────────────────────────────────────────

    @Test
    void deleteOldEntriesPerInstance_keepN_deletesOldest() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 5; i++) {
            save("inst-1", "job-" + i, true, base.plusSeconds(i));
        }
        assertThat(repository.count()).isEqualTo(5);

        repository.deleteOldEntriesPerInstance(3);

        assertThat(repository.count()).isEqualTo(3);
        // Die 3 neuesten müssen erhalten bleiben
        assertThat(repository.findLatestForInstance("inst-1"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getBackupJobId()).isEqualTo("job-5"));
    }

    @Test
    void deleteOldEntriesPerInstance_perInstance_respectsPartitioning() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // 4 Einträge für inst-1, 3 für inst-2
        for (int i = 1; i <= 4; i++) save("inst-1", "a-job-" + i, true, base.plusSeconds(i));
        for (int i = 1; i <= 3; i++) save("inst-2", "b-job-" + i, true, base.plusSeconds(i));

        repository.deleteOldEntriesPerInstance(2);

        long inst1Count = repository.findAll().stream()
                .filter(e -> "inst-1".equals(e.getInstanceId())).count();
        long inst2Count = repository.findAll().stream()
                .filter(e -> "inst-2".equals(e.getInstanceId())).count();

        assertThat(inst1Count).isEqualTo(2);
        assertThat(inst2Count).isEqualTo(2);
    }

    @Test
    void deleteOldEntriesPerInstance_keepNGreaterThanTotal_deletesNothing() {
        save("inst-1", "job-1", true, Instant.now());
        save("inst-1", "job-2", true, Instant.now().minusSeconds(60));

        repository.deleteOldEntriesPerInstance(100);

        assertThat(repository.count()).isEqualTo(2);
    }

    // ── Hilfsmethode ─────────────────────────────────────────────────────────

    private void save(String instanceId, String jobId, boolean allPassed, Instant checkedAt) {
        S3CheckResultEntity e = new S3CheckResultEntity();
        e.setManagerId("mgr-1");
        e.setInstanceId(instanceId);
        e.setBackupJobId(jobId);
        e.setAllPassed(allPassed);
        e.setExists(allPassed);
        e.setAccessible(allPassed);
        e.setSizeMatchWithinTolerance(allPassed);
        e.setMagicBytesValid(allPassed);
        e.setCheckedAt(checkedAt);
        repository.save(e);
    }
}
