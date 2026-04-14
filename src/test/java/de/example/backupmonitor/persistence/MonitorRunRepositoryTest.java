package de.example.backupmonitor.persistence;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LiquibaseAutoConfiguration.class)
@Testcontainers
class MonitorRunRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MonitorRunRepository repository;

    // ── deleteOldEntriesPerInstanceAndType ────────────────────────────────────

    @Test
    void deleteOldEntriesPerInstanceAndType_keepN_deletesOldest() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 5; i++) {
            save("mgr-1", "inst-1", RunType.JOB_CHECK.name(), base.plusSeconds(i));
        }

        repository.deleteOldEntriesPerInstanceAndType(RunType.JOB_CHECK.name(), 3);

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void deleteOldEntriesPerInstanceAndType_onlyAffectsMatchingType() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 4; i++) {
            save("mgr-1", "inst-1", RunType.JOB_CHECK.name(),     base.plusSeconds(i));
            save("mgr-1", "inst-1", RunType.RESTORE_TEST.name(),  base.plusSeconds(i));
        }

        repository.deleteOldEntriesPerInstanceAndType(RunType.JOB_CHECK.name(), 2);

        long jobCheckCount = repository.findAll().stream()
                .filter(r -> RunType.JOB_CHECK.name().equals(r.getRunType())).count();
        long restoreCount = repository.findAll().stream()
                .filter(r -> RunType.RESTORE_TEST.name().equals(r.getRunType())).count();

        assertThat(jobCheckCount).isEqualTo(2);
        assertThat(restoreCount).isEqualTo(4); // unberührt
    }

    @Test
    void deleteOldEntriesPerInstanceAndType_perInstance_respectsPartitioning() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 5; i++) {
            save("mgr-1", "inst-1", RunType.JOB_CHECK.name(), base.plusSeconds(i));
            save("mgr-1", "inst-2", RunType.JOB_CHECK.name(), base.plusSeconds(i));
        }

        repository.deleteOldEntriesPerInstanceAndType(RunType.JOB_CHECK.name(), 2);

        long inst1Count = repository.findAll().stream()
                .filter(r -> "inst-1".equals(r.getInstanceId())).count();
        long inst2Count = repository.findAll().stream()
                .filter(r -> "inst-2".equals(r.getInstanceId())).count();

        assertThat(inst1Count).isEqualTo(2);
        assertThat(inst2Count).isEqualTo(2);
    }

    // ── findLatestPerInstance ─────────────────────────────────────────────────

    @Test
    void findLatestPerInstance_returnsNewestPerInstanceAndType() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");

        save("mgr-1", "inst-1", RunType.JOB_CHECK.name(), t1);
        save("mgr-1", "inst-1", RunType.JOB_CHECK.name(), t2);
        save("mgr-1", "inst-2", RunType.JOB_CHECK.name(), t1);

        List<MonitorRun> latest = repository.findLatestPerInstance();

        // Je eine neueste pro (instance, type) Kombination
        assertThat(latest).hasSize(2);
        assertThat(latest).anyMatch(r ->
                "inst-1".equals(r.getInstanceId()) && r.getStartedAt().equals(t2));
        assertThat(latest).anyMatch(r ->
                "inst-2".equals(r.getInstanceId()) && r.getStartedAt().equals(t1));
    }

    @Test
    void findLatestPerInstance_empty_returnsEmptyList() {
        assertThat(repository.findLatestPerInstance()).isEmpty();
    }

    // ── Hilfsmethode ─────────────────────────────────────────────────────────

    private void save(String managerId, String instanceId, String runType, Instant startedAt) {
        MonitorRun run = new MonitorRun();
        run.setManagerId(managerId);
        run.setInstanceId(instanceId);
        run.setInstanceName(instanceId);
        run.setRunType(runType);
        run.setStartedAt(startedAt);
        run.setFinishedAt(startedAt.plusSeconds(10));
        run.setStatus("OK");
        repository.save(run);
    }
}
