package de.example.backupmonitor.monitor;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.metrics.MetricsPublisher;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import de.example.backupmonitor.persistence.MonitorRun;
import de.example.backupmonitor.persistence.MonitorRunRepository;
import de.example.backupmonitor.persistence.RetentionCleanupJob;
import de.example.backupmonitor.s3.S3VerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringOrchestratorTest {

    @Mock private BackupPlanMonitor planMonitor;
    @Mock private BackupJobMonitor jobMonitor;
    @Mock private RestoreTestMonitor restoreTestMonitor;
    @Mock private S3VerificationService s3VerificationService;
    @Mock private MetricsPublisher metrics;
    @Mock private MonitorRunRepository monitorRunRepo;
    @Mock private RetentionCleanupJob retentionCleanup;

    private MonitoringOrchestrator orchestrator;

    private static final String MGR_ID = "mgr-1";
    private static final String INST_ID = "inst-1";
    private static final String INST_NAME = "Instance 1";

    @BeforeEach
    void setUp() {
        MonitoringConfig config = buildConfig(true);
        orchestrator = new MonitoringOrchestrator(
                config, planMonitor, jobMonitor, restoreTestMonitor,
                s3VerificationService, metrics, monitorRunRepo, retentionCleanup);

        lenient().when(monitorRunRepo.save(any())).thenAnswer(inv -> {
            MonitorRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            return r;
        });
    }

    // ── runJobChecks ──────────────────────────────────────────────────────────

    @Test
    void runJobChecks_planOkJobSucceeded_incrementsSuccess() {
        BackupPlan plan = activePlan();
        BackupJob job = succeededJob();
        when(planMonitor.checkPlan(MGR_ID, INST_ID)).thenReturn(PlanCheckResult.ok(plan));
        when(jobMonitor.checkLatestJob(eq(MGR_ID), eq(INST_ID), any()))
                .thenReturn(JobCheckResult.success(job));

        orchestrator.runJobChecks();

        verify(metrics).incrementJobSuccess(MGR_ID, INST_ID, INST_NAME);
        verify(retentionCleanup).cleanup();
    }

    @Test
    void runJobChecks_planFailed_incrementsFailureAndSkipsJobCheck() {
        when(planMonitor.checkPlan(MGR_ID, INST_ID))
                .thenReturn(PlanCheckResult.failed("no plan"));

        orchestrator.runJobChecks();

        verify(metrics).incrementJobFailure(MGR_ID, INST_ID, INST_NAME);
        verifyNoInteractions(jobMonitor);
    }

    @Test
    void runJobChecks_jobFailed_incrementsFailure() {
        BackupPlan plan = activePlan();
        when(planMonitor.checkPlan(MGR_ID, INST_ID)).thenReturn(PlanCheckResult.ok(plan));
        when(jobMonitor.checkLatestJob(eq(MGR_ID), eq(INST_ID), any()))
                .thenReturn(JobCheckResult.failed("FAILED"));

        orchestrator.runJobChecks();

        verify(metrics).incrementJobFailure(MGR_ID, INST_ID, INST_NAME);
    }

    @Test
    void runJobChecks_s3Disabled_skipsS3Verification() {
        MonitoringConfig config = buildConfig(false);
        orchestrator = new MonitoringOrchestrator(
                config, planMonitor, jobMonitor, restoreTestMonitor,
                s3VerificationService, metrics, monitorRunRepo, retentionCleanup);

        BackupPlan plan = activePlan();
        BackupJob job = succeededJob();
        when(planMonitor.checkPlan(MGR_ID, INST_ID)).thenReturn(PlanCheckResult.ok(plan));
        when(jobMonitor.checkLatestJob(eq(MGR_ID), eq(INST_ID), any()))
                .thenReturn(JobCheckResult.success(job));

        orchestrator.runJobChecks();

        verifyNoInteractions(s3VerificationService);
    }

    @Test
    void runJobChecks_exceptionThrown_completesRunWithError() {
        when(planMonitor.checkPlan(MGR_ID, INST_ID))
                .thenThrow(new RuntimeException("unexpected"));

        orchestrator.runJobChecks();

        // Should save a run with ERROR status — verify save was called at least twice
        // (once for RUNNING, once for ERROR)
        verify(monitorRunRepo, atLeast(2)).save(any());
    }

    // ── runRestoreTests ───────────────────────────────────────────────────────

    @Test
    void runRestoreTests_enabled_delegatesToRestoreTestMonitor() {
        orchestrator.runRestoreTests();

        verify(restoreTestMonitor).runAll();
    }

    @Test
    void runRestoreTests_disabled_doesNothing() {
        MonitoringConfig config = buildConfig(true);
        config.getRestoreTest().setEnabled(false);
        orchestrator = new MonitoringOrchestrator(
                config, planMonitor, jobMonitor, restoreTestMonitor,
                s3VerificationService, metrics, monitorRunRepo, retentionCleanup);

        orchestrator.runRestoreTests();

        verifyNoInteractions(restoreTestMonitor);
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private MonitoringConfig buildConfig(boolean s3Enabled) {
        MonitoringConfig config = new MonitoringConfig();
        config.getS3Verification().setEnabled(s3Enabled);
        config.getRestoreTest().setEnabled(true);

        MonitoringConfig.ManagerConfig mgr = new MonitoringConfig.ManagerConfig();
        mgr.setId(MGR_ID);
        MonitoringConfig.ServiceInstanceConfig inst = new MonitoringConfig.ServiceInstanceConfig();
        inst.setId(INST_ID);
        inst.setName(INST_NAME);
        mgr.setInstances(List.of(inst));
        config.setManagers(List.of(mgr));
        return config;
    }

    private BackupPlan activePlan() {
        BackupPlan plan = new BackupPlan();
        plan.setPaused(false);
        plan.setActive(true);
        return plan;
    }

    private BackupJob succeededJob() {
        BackupJob job = new BackupJob();
        job.setId("job-1");
        job.setStatus(JobStatus.SUCCEEDED);
        job.setEndDate(Instant.now().minusSeconds(3600));
        return job;
    }
}
