package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.AgentClient;
import de.example.backupmonitor.client.AgentClient.AgentRestoreStatus;
import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.metrics.MetricsPublisher;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.S3FileDestination;
import de.example.backupmonitor.model.JobStatus;
import de.example.backupmonitor.persistence.MonitorRun;
import de.example.backupmonitor.persistence.MonitorRunRepository;
import de.example.backupmonitor.persistence.RestoreTestResultRepository;
import de.example.backupmonitor.s3.S3CheckResultRepository;
import de.example.backupmonitor.sandbox.InsufficientResourcesException;
import de.example.backupmonitor.sandbox.SandboxConnection;
import de.example.backupmonitor.sandbox.SandboxManager;
import de.example.backupmonitor.sandbox.SandboxProvisioner;
import de.example.backupmonitor.validation.DatabaseContentChecker;
import de.example.backupmonitor.validation.QueryCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestoreTestMonitorTest {

    @Mock private BackupManagerClient managerClient;
    @Mock private AgentClient agentClient;
    @Mock private SandboxManager sandboxManager;
    @Mock private SandboxProvisioner sandboxProvisioner;
    @Mock private DatabaseContentChecker contentChecker;
    @Mock private S3CheckResultRepository s3CheckResultRepo;
    @Mock private MetricsPublisher metrics;
    @Mock private MonitorRunRepository monitorRunRepo;
    @Mock private RestoreTestResultRepository restoreResultRepo;

    private static final String MGR_ID   = "mgr-1";
    private static final String INST_ID  = "inst-1";
    private static final String INST_NAME = "Instance 1";

    @BeforeEach
    void setUp() {
        when(monitorRunRepo.save(any())).thenAnswer(inv -> {
            MonitorRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(42L);
            return r;
        });
        // S3-Prüfung: kein bestandener S3-Check → fallback zu managerClient
        when(s3CheckResultRepo.findLatestPassedForInstance(anyString()))
                .thenReturn(Optional.empty());
    }

    // ── Erfolgsfall ───────────────────────────────────────────────────────────

    @Test
    void runAll_successPath_recordsOkAndPersistsResult() {
        BackupJob job = backupJobWithFile();
        when(managerClient.getLatestBackupJob(MGR_ID, INST_ID)).thenReturn(Optional.of(job));

        SandboxConnection sandbox = sandbox();
        when(sandboxManager.getSandbox(INST_ID)).thenReturn(sandbox);

        // pollStatus gibt sofort SUCCEEDED zurück → kein Thread.sleep
        when(agentClient.pollStatus(eq(MGR_ID), anyString()))
                .thenReturn(AgentRestoreStatus.SUCCEEDED);

        QueryCheckResult passing = QueryCheckResult.ok("check", "SELECT 1", 1L, 1L);
        when(contentChecker.runChecks(sandbox, INST_ID)).thenReturn(List.of(passing));

        buildMonitor().runAll();

        verify(metrics).recordRestoreResult(
                eq(MGR_ID), eq(INST_ID), eq(INST_NAME), any(RestoreTestResult.class));
        verify(restoreResultRepo).save(any());
    }

    // ── Agent-Restore scheitert ───────────────────────────────────────────────

    @Test
    void runAll_restoreFailed_recordsFailedResult() {
        BackupJob job = backupJobWithFile();
        when(managerClient.getLatestBackupJob(MGR_ID, INST_ID)).thenReturn(Optional.of(job));
        when(sandboxManager.getSandbox(INST_ID)).thenReturn(sandbox());
        when(agentClient.pollStatus(eq(MGR_ID), anyString()))
                .thenReturn(AgentRestoreStatus.FAILED);

        buildMonitor().runAll();

        verify(metrics).recordRestoreResult(
                eq(MGR_ID), eq(INST_ID), eq(INST_NAME),
                argThat(r -> r.status() == RestoreTestResult.Status.FAILED));
    }

    // ── InsufficientResources ─────────────────────────────────────────────────

    @Test
    void runAll_insufficientResources_recordsNoResourcesResult() {
        when(managerClient.getLatestBackupJob(MGR_ID, INST_ID)).thenReturn(Optional.of(backupJobWithFile()));
        when(sandboxManager.getSandbox(INST_ID))
                .thenThrow(new InsufficientResourcesException("quota full"));

        buildMonitor().runAll();

        verify(metrics).recordRestoreResult(
                eq(MGR_ID), eq(INST_ID), eq(INST_NAME),
                argThat(r -> r.status() == RestoreTestResult.Status.NO_RESOURCES));
    }

    // ── Kein Backup-Job vorhanden ─────────────────────────────────────────────

    @Test
    void runAll_noBackupJob_recordsErrorResult() {
        when(managerClient.getLatestBackupJob(MGR_ID, INST_ID)).thenReturn(Optional.empty());

        buildMonitor().runAll();

        verify(metrics).recordRestoreResult(
                eq(MGR_ID), eq(INST_ID), eq(INST_NAME),
                argThat(r -> r.status() == RestoreTestResult.Status.ERROR));
    }

    // ── S3-verifizierter Job wird bevorzugt ───────────────────────────────────

    @Test
    void runAll_s3PassedJobAvailable_usesS3VerifiedJob() {
        de.example.backupmonitor.s3.S3CheckResultEntity s3Entity =
                new de.example.backupmonitor.s3.S3CheckResultEntity();
        s3Entity.setBackupJobId("job-s3");
        when(s3CheckResultRepo.findLatestPassedForInstance(INST_ID))
                .thenReturn(Optional.of(s3Entity));

        BackupJob s3Job = backupJobWithFile();
        s3Job.setId("job-s3");
        when(managerClient.getJobById(MGR_ID, "job-s3")).thenReturn(Optional.of(s3Job));

        SandboxConnection sandbox = sandbox();
        when(sandboxManager.getSandbox(INST_ID)).thenReturn(sandbox);
        when(agentClient.pollStatus(eq(MGR_ID), anyString()))
                .thenReturn(AgentRestoreStatus.SUCCEEDED);
        when(contentChecker.runChecks(sandbox, INST_ID))
                .thenReturn(List.of(QueryCheckResult.ok("check", "SELECT 1", 1L, 1L)));

        buildMonitor().runAll();

        verify(managerClient).getJobById(MGR_ID, "job-s3");
        verify(managerClient, never()).getLatestBackupJob(anyString(), anyString());
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private RestoreTestMonitor buildMonitor() {
        MonitoringConfig config = new MonitoringConfig();
        config.getRestoreTest().setEnabled(true);
        config.getRestoreTest().setMaxParallel(1);
        config.getRestoreTest().setTimeoutMinutes(1);

        MonitoringConfig.ManagerConfig mgr = new MonitoringConfig.ManagerConfig();
        mgr.setId(MGR_ID);
        MonitoringConfig.ServiceInstanceConfig inst = new MonitoringConfig.ServiceInstanceConfig();
        inst.setId(INST_ID);
        inst.setName(INST_NAME);
        mgr.setInstances(List.of(inst));
        config.setManagers(List.of(mgr));

        return new RestoreTestMonitor(config, managerClient, agentClient, sandboxManager,
                sandboxProvisioner, contentChecker, s3CheckResultRepo, metrics,
                monitorRunRepo, restoreResultRepo);
    }

    private BackupJob backupJobWithFile() {
        BackupJob job = new BackupJob();
        job.setId("job-1");
        job.setStatus(JobStatus.SUCCEEDED);
        job.setFiles(Map.of("agent-1", "backup-2026-01-01.gz"));
        job.setDestination(new S3FileDestination());
        return job;
    }

    private SandboxConnection sandbox() {
        return new SandboxConnection("sandbox-host", 5432, "sandbox-db",
                "user", "pass", null, false);
    }
}
