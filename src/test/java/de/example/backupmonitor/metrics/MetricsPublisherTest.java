package de.example.backupmonitor.metrics;

import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import de.example.backupmonitor.monitor.RestoreTestResult;
import de.example.backupmonitor.s3.S3CheckResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsPublisherTest {

    private SimpleMeterRegistry registry;
    private MetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        publisher = new MetricsPublisher(registry);
    }

    // ── recordPlanStatus ──────────────────────────────────────────────────────

    @Test
    void recordPlanStatus_activePlan_setsGaugesCorrectly() {
        BackupPlan plan = new BackupPlan();
        plan.setPaused(false);

        publisher.recordPlanStatus("mgr", "inst", "name", plan);

        assertGaugeValue(MetricNames.PLAN_ACTIVE, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.PLAN_PAUSED, "mgr", "inst", "name", 0.0);
    }

    @Test
    void recordPlanStatus_pausedPlan_setsGaugesCorrectly() {
        BackupPlan plan = new BackupPlan();
        plan.setPaused(true);

        publisher.recordPlanStatus("mgr", "inst", "name", plan);

        assertGaugeValue(MetricNames.PLAN_ACTIVE, "mgr", "inst", "name", 0.0);
        assertGaugeValue(MetricNames.PLAN_PAUSED, "mgr", "inst", "name", 1.0);
    }

    @Test
    void recordPlanStatus_nullPlan_setsZero() {
        publisher.recordPlanStatus("mgr", "inst", "name", null);

        assertGaugeValue(MetricNames.PLAN_ACTIVE, "mgr", "inst", "name", 0.0);
        assertGaugeValue(MetricNames.PLAN_PAUSED, "mgr", "inst", "name", 0.0);
    }

    // ── recordJobResult ───────────────────────────────────────────────────────

    @Test
    void recordJobResult_succeededJob_setsStatusToOne() {
        BackupJob job = new BackupJob();
        job.setStatus(JobStatus.SUCCEEDED);
        job.setEndDate(Instant.now().minusSeconds(3600));

        publisher.recordJobResult("mgr", "inst", "name", job, null, 25);

        assertGaugeValue(MetricNames.JOB_LAST_STATUS, "mgr", "inst", "name", 1.0);
    }

    @Test
    void recordJobResult_failedJob_setsStatusToZero() {
        BackupJob job = new BackupJob();
        job.setStatus(JobStatus.FAILED);

        publisher.recordJobResult("mgr", "inst", "name", job, null, 25);

        assertGaugeValue(MetricNames.JOB_LAST_STATUS, "mgr", "inst", "name", 0.0);
    }

    @Test
    void recordJobResult_nullJob_setsDefaultValues() {
        publisher.recordJobResult("mgr", "inst", "name", null, null, 25);

        assertGaugeValue(MetricNames.JOB_LAST_STATUS, "mgr", "inst", "name", 0.0);
        assertGaugeValue(MetricNames.JOB_LAST_AGE_HOURS, "mgr", "inst", "name", -1.0);
        assertGaugeValue(MetricNames.JOB_LAST_FILESIZE, "mgr", "inst", "name", 0.0);
    }

    @Test
    void recordJobResult_overdueJob_setsOverdueToOne() {
        BackupJob job = new BackupJob();
        job.setStatus(JobStatus.SUCCEEDED);
        // Job vor 30 Stunden – bei täglichem Cron (24h) + 25% Toleranz (30h) knapp überfällig
        job.setEndDate(Instant.now().minusSeconds(31 * 3600L));

        BackupPlan plan = new BackupPlan();
        plan.setFrequency("0 0 2 * * *"); // täglich 02:00

        publisher.recordJobResult("mgr", "inst", "name", job, plan, 25);

        assertGaugeValue(MetricNames.JOB_OVERDUE, "mgr", "inst", "name", 1.0);
    }

    @Test
    void recordJobResult_recentJob_setsOverdueToZero() {
        BackupJob job = new BackupJob();
        job.setStatus(JobStatus.SUCCEEDED);
        job.setEndDate(Instant.now().minusSeconds(3600));

        BackupPlan plan = new BackupPlan();
        plan.setFrequency("0 0 2 * * *");

        publisher.recordJobResult("mgr", "inst", "name", job, plan, 25);

        assertGaugeValue(MetricNames.JOB_OVERDUE, "mgr", "inst", "name", 0.0);
    }

    @Test
    void recordJobResult_nullPlan_setsOverdueToMinusOne() {
        BackupJob job = new BackupJob();
        job.setStatus(JobStatus.SUCCEEDED);
        job.setEndDate(Instant.now().minusSeconds(3600));

        publisher.recordJobResult("mgr", "inst", "name", job, null, 25);

        assertGaugeValue(MetricNames.JOB_OVERDUE, "mgr", "inst", "name", -1.0);
    }

    // ── recordConsecutiveFailures ────────────────────────────────────────────

    @Test
    void recordConsecutiveFailures_setsGaugeCorrectly() {
        publisher.recordConsecutiveFailures("mgr", "inst", "name", 3);

        assertGaugeValue(MetricNames.JOB_CONSECUTIVE_FAILURES, "mgr", "inst", "name", 3.0);
    }

    // ── recordPlanHasSucceededJob ─────────────────────────────────────────────

    @Test
    void recordPlanHasSucceededJob_true_setsGaugeToOne() {
        publisher.recordPlanHasSucceededJob("mgr", "inst", "name", true);

        assertGaugeValue(MetricNames.PLAN_HAS_SUCCEEDED_JOB, "mgr", "inst", "name", 1.0);
    }

    @Test
    void recordPlanHasSucceededJob_false_setsGaugeToZero() {
        publisher.recordPlanHasSucceededJob("mgr", "inst", "name", false);

        assertGaugeValue(MetricNames.PLAN_HAS_SUCCEEDED_JOB, "mgr", "inst", "name", 0.0);
    }

    // ── incrementJobSuccess / Failure ─────────────────────────────────────────

    @Test
    void incrementJobSuccess_incrementsCounter() {
        publisher.incrementJobSuccess("mgr", "inst", "name");
        publisher.incrementJobSuccess("mgr", "inst", "name");

        Counter c = registry.counter(MetricNames.JOB_SUCCESS_TOTAL,
                "manager_id", "mgr", "instance_id", "inst", "instance_name", "name");
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void incrementJobFailure_incrementsCounter() {
        publisher.incrementJobFailure("mgr", "inst", "name");

        Counter c = registry.counter(MetricNames.JOB_FAILURE_TOTAL,
                "manager_id", "mgr", "instance_id", "inst", "instance_name", "name");
        assertThat(c.count()).isEqualTo(1.0);
    }

    // ── recordRestoreResult ───────────────────────────────────────────────────

    @Test
    void recordRestoreResult_ok_setsStatusToOne() {
        RestoreTestResult result = RestoreTestResult.ok("inst", List.of(), 120L);

        publisher.recordRestoreResult("mgr", "inst", "name", result);

        assertGaugeValue(MetricNames.RESTORE_LAST_STATUS, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.RESTORE_LAST_DURATION_SEC, "mgr", "inst", "name", 120.0);
        assertGaugeValue(MetricNames.RESTORE_VALIDATION_PASSED, "mgr", "inst", "name", 1.0);
    }

    @Test
    void recordRestoreResult_noResources_setsStatusToMinusTwo() {
        RestoreTestResult result = RestoreTestResult.noResources("inst", "quota exceeded");

        publisher.recordRestoreResult("mgr", "inst", "name", result);

        assertGaugeValue(MetricNames.RESTORE_LAST_STATUS, "mgr", "inst", "name", -2.0);
    }

    // ── recordS3CheckResult ───────────────────────────────────────────────────

    @Test
    void recordS3CheckResult_allPassed_setsAllGaugesToOne() {
        S3CheckResult result = S3CheckResult.builder()
                .managerId("mgr").instanceId("inst")
                .exists(true).accessible(true)
                .sizeMatch(true).magicBytesValid(true)
                .sizeActualBytes(1024L)
                .allPassed(true)
                .build();

        publisher.recordS3CheckResult("mgr", "inst", "name", result);

        assertGaugeValue(MetricNames.S3_FILE_EXISTS, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.S3_SIZE_MATCH, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.S3_ACCESSIBLE, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.S3_MAGIC_BYTES_VALID, "mgr", "inst", "name", 1.0);
        assertGaugeValue(MetricNames.S3_ALL_CHECKS_PASSED, "mgr", "inst", "name", 1.0);
    }

    // ── recordMonitorRun ──────────────────────────────────────────────────────

    @Test
    void recordMonitorRun_setsTimestampGauge() {
        Instant now = Instant.now();

        publisher.recordMonitorRun("mgr", now);

        Gauge gauge = registry.find(MetricNames.MONITOR_LAST_RUN)
                .tag("manager_id", "mgr").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(now.getEpochSecond());
    }

    @Test
    void recordMonitorRun_calledTwice_updatesTimestamp() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");

        publisher.recordMonitorRun("mgr", t1);
        publisher.recordMonitorRun("mgr", t2);

        Gauge gauge = registry.find(MetricNames.MONITOR_LAST_RUN)
                .tag("manager_id", "mgr").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(t2.getEpochSecond());
    }

    // ── Hilfsmethode ─────────────────────────────────────────────────────────

    private void assertGaugeValue(String name, String managerId, String instanceId,
                                   String instanceName, double expected) {
        Gauge gauge = registry.find(name)
                .tag("manager_id", managerId)
                .tag("instance_id", instanceId)
                .tag("instance_name", instanceName)
                .gauge();
        assertThat(gauge).as("Gauge %s not found", name).isNotNull();
        assertThat(gauge.value()).as("Gauge %s value", name).isEqualTo(expected);
    }
}
