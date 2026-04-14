package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.AgentClient;
import de.example.backupmonitor.client.AgentClient.AgentRestoreStatus;
import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.metrics.MetricsPublisher;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.FileDestination;
import de.example.backupmonitor.persistence.MonitorRun;
import de.example.backupmonitor.persistence.MonitorRunRepository;
import de.example.backupmonitor.persistence.RestoreTestResultEntity;
import de.example.backupmonitor.persistence.RestoreTestResultRepository;
import de.example.backupmonitor.persistence.RunType;
import de.example.backupmonitor.s3.S3CheckResultRepository;
import de.example.backupmonitor.sandbox.InsufficientResourcesException;
import de.example.backupmonitor.sandbox.SandboxConnection;
import de.example.backupmonitor.sandbox.SandboxManager;
import de.example.backupmonitor.sandbox.SandboxProvisioner;
import de.example.backupmonitor.validation.DatabaseContentChecker;
import de.example.backupmonitor.validation.QueryCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RestoreTestMonitor {

    private final MonitoringConfig config;
    private final BackupManagerClient managerClient;
    private final AgentClient agentClient;
    private final SandboxManager sandboxManager;
    private final SandboxProvisioner sandboxProvisioner;
    private final DatabaseContentChecker contentChecker;
    private final S3CheckResultRepository s3CheckResultRepo;
    private final MetricsPublisher metrics;
    private final MonitorRunRepository monitorRunRepo;
    private final RestoreTestResultRepository restoreResultRepo;

    public void runAll() {
        int maxParallel = config.getRestoreTest().getMaxParallel();
        ExecutorService executor = Executors.newFixedThreadPool(maxParallel);
        List<Future<RestoreTestResult>> futures = new ArrayList<>();

        for (MonitoringConfig.ManagerConfig manager : config.getManagers()) {
            for (MonitoringConfig.ServiceInstanceConfig instance : manager.getInstances()) {
                futures.add(executor.submit(() ->
                        runSingleRestoreTest(manager.getId(), instance)));
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(
                    (long) config.getRestoreTest().getTimeoutMinutes() * futures.size(),
                    TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Restore test execution interrupted");
        }
    }

    private RestoreTestResult runSingleRestoreTest(String managerId,
                                                    MonitoringConfig.ServiceInstanceConfig instance) {
        String instanceId = instance.getId();
        MonitorRun run = startRun(managerId, instanceId, instance.getName());
        Instant start = Instant.now();

        try {
            BackupJob job = findBestJobForRestore(managerId, instanceId);
            SandboxConnection sandbox = sandboxManager.getSandbox(instanceId);

            try {
                sandboxProvisioner.reset(sandbox);

                String agentJobId = UUID.randomUUID().toString();
                agentClient.triggerRestore(
                        managerId, agentJobId,
                        job.getDestination(),
                        resolveFilename(job),
                        sandbox);

                AgentRestoreStatus agentStatus = pollWithTimeout(managerId, agentJobId);

                if (agentStatus != AgentRestoreStatus.SUCCEEDED) {
                    RestoreTestResult result = RestoreTestResult.restoreFailed(instanceId, agentStatus);
                    long duration = Duration.between(start, Instant.now()).toSeconds();
                    metrics.recordRestoreResult(managerId, instanceId, instance.getName(), result);
                    completeRun(run, "FAILED", result.errorMessage());
                    persistRestoreResult(run.getId(), result, job, sandbox, duration);
                    return result;
                }

                List<QueryCheckResult> checks = contentChecker.runChecks(sandbox, instanceId);
                long duration = Duration.between(start, Instant.now()).toSeconds();

                RestoreTestResult result = checks.stream().allMatch(QueryCheckResult::passed)
                        ? RestoreTestResult.ok(instanceId, checks, duration)
                        : RestoreTestResult.validationFailed(instanceId, checks);

                metrics.recordRestoreResult(managerId, instanceId, instance.getName(), result);
                completeRun(run, result.status() == RestoreTestResult.Status.OK ? "OK" : "FAILED",
                        result.errorMessage());
                persistRestoreResult(run.getId(), result, job, sandbox, duration);
                return result;

            } finally {
                try {
                    sandboxProvisioner.reset(sandbox);
                } catch (Exception e) {
                    log.warn("Failed to reset sandbox after restore test: {}", e.getMessage());
                }
            }

        } catch (InsufficientResourcesException e) {
            RestoreTestResult result = RestoreTestResult.noResources(instanceId, e.getMessage());
            metrics.recordRestoreResult(managerId, instanceId, instance.getName(), result);
            completeRun(run, "SKIPPED", e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("Restore test failed for instance {}: {}", instanceId, e.getMessage(), e);
            RestoreTestResult result = RestoreTestResult.error(instanceId, e.getMessage());
            metrics.recordRestoreResult(managerId, instanceId, instance.getName(), result);
            completeRun(run, "ERROR", e.getMessage());
            return result;
        }
    }

    private BackupJob findBestJobForRestore(String managerId, String instanceId) {
        return s3CheckResultRepo
                .findLatestPassedForInstance(instanceId)
                .flatMap(s3Check ->
                        managerClient.getJobById(managerId, s3Check.getBackupJobId()))
                .orElseGet(() ->
                        managerClient.getLatestBackupJob(managerId, instanceId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "No backup job found for restore test of instance: "
                                                + instanceId)));
    }

    private AgentRestoreStatus pollWithTimeout(String managerId, String agentJobId) {
        Instant deadline = Instant.now().plusSeconds(
                config.getRestoreTest().getTimeoutMinutes() * 60L);
        while (Instant.now().isBefore(deadline)) {
            AgentRestoreStatus status = agentClient.pollStatus(managerId, agentJobId);
            if (status == AgentRestoreStatus.SUCCEEDED
                    || status == AgentRestoreStatus.FAILED) {
                return status;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentRestoreStatus.UNKNOWN;
            }
        }
        return AgentRestoreStatus.TIMEOUT;
    }

    private String resolveFilename(BackupJob job) {
        if (job.getFiles() != null && !job.getFiles().isEmpty()) {
            return job.getFiles().values().iterator().next();
        }
        if (job.getAgentExecutionReponses() != null) {
            return job.getAgentExecutionReponses().values().stream()
                    .map(r -> r.getFilename())
                    .filter(f -> f != null && !f.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No filename in job " + job.getIdAsString()));
        }
        throw new IllegalStateException("No filename in job " + job.getIdAsString());
    }

    private MonitorRun startRun(String managerId, String instanceId, String instanceName) {
        MonitorRun run = new MonitorRun();
        run.setManagerId(managerId);
        run.setInstanceId(instanceId);
        run.setInstanceName(instanceName);
        run.setRunType(RunType.RESTORE_TEST.name());
        run.setStartedAt(Instant.now());
        run.setStatus("RUNNING");
        return monitorRunRepo.save(run);
    }

    private void completeRun(MonitorRun run, String status, String errorMessage) {
        run.setFinishedAt(Instant.now());
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        monitorRunRepo.save(run);
    }

    private void persistRestoreResult(Long monitorRunId, RestoreTestResult result,
                                       BackupJob job, SandboxConnection sandbox,
                                       long durationSeconds) {
        try {
            RestoreTestResultEntity entity = RestoreTestResultEntity.from(
                    monitorRunId, result,
                    job != null ? job.getIdAsString() : null,
                    job != null ? resolveFilenameOrNull(job) : null,
                    sandbox != null && sandbox.provisioned() ? "PROVISION" : "EXISTING");
            restoreResultRepo.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist restore result: {}", e.getMessage());
        }
    }

    private String resolveFilenameOrNull(BackupJob job) {
        try {
            return resolveFilename(job);
        } catch (Exception e) {
            return null;
        }
    }
}
