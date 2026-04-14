package de.example.backupmonitor.monitor;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.metrics.MetricsPublisher;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.persistence.MonitorRun;
import de.example.backupmonitor.persistence.MonitorRunRepository;
import de.example.backupmonitor.persistence.RetentionCleanupJob;
import de.example.backupmonitor.persistence.RunType;
import de.example.backupmonitor.s3.S3CheckResult;
import de.example.backupmonitor.s3.S3VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonitoringOrchestrator {

    private final MonitoringConfig config;
    private final BackupPlanMonitor planMonitor;
    private final BackupJobMonitor jobMonitor;
    private final RestoreTestMonitor restoreTestMonitor;
    private final S3VerificationService s3VerificationService;
    private final MetricsPublisher metrics;
    private final MonitorRunRepository monitorRunRepo;
    private final RetentionCleanupJob retentionCleanup;

    public void runJobChecks() {
        log.info("Running job checks for {} manager(s)", config.getManagers().size());
        Instant runStart = Instant.now();

        for (MonitoringConfig.ManagerConfig manager : config.getManagers()) {
            for (MonitoringConfig.ServiceInstanceConfig instance : manager.getInstances()) {
                runInstanceCheck(manager, instance);
            }
            metrics.recordMonitorRun(manager.getId(), runStart);
        }

        retentionCleanup.cleanup();
        log.info("Job checks completed");
    }

    private void runInstanceCheck(MonitoringConfig.ManagerConfig manager,
                                   MonitoringConfig.ServiceInstanceConfig instance) {
        MonitorRun run = startRun(manager.getId(), instance.getId(),
                instance.getName(), RunType.JOB_CHECK);
        try {
            // ① Plan prüfen
            PlanCheckResult planResult = planMonitor.checkPlan(manager.getId(), instance.getId());
            metrics.recordPlanStatus(manager.getId(), instance.getId(),
                    instance.getName(), planResult.getPlan());

            if (!planResult.isOk()) {
                completeRun(run, "FAILED", planResult.getMessage(), null);
                metrics.incrementJobFailure(manager.getId(), instance.getId(), instance.getName());
                return;
            }

            // ② Letzten Job prüfen
            JobCheckResult jobResult = jobMonitor.checkLatestJob(
                    manager.getId(), instance.getId(), planResult.getPlan());
            metrics.recordJobResult(manager.getId(), instance.getId(),
                    instance.getName(), jobResult.getJob());

            // ③ S3-Verifikation (nur wenn Job SUCCEEDED)
            if (jobResult.isSuccess() && jobResult.getJob() != null
                    && config.getS3Verification().isEnabled()) {
                try {
                    S3CheckResult s3Result = s3VerificationService.verify(
                            manager.getId(), instance.getId(),
                            instance.getName(), jobResult.getJob());
                    jobResult.setS3CheckResult(s3Result);
                } catch (Exception e) {
                    log.warn("S3 verification failed for instance {}: {}",
                            instance.getId(), e.getMessage());
                }
            }

            if (jobResult.isSuccess()) {
                metrics.incrementJobSuccess(manager.getId(), instance.getId(), instance.getName());
                completeRun(run, "OK", null, buildJobDetails(jobResult.getJob()));
            } else {
                metrics.incrementJobFailure(manager.getId(), instance.getId(), instance.getName());
                completeRun(run, "FAILED", jobResult.getMessage(), null);
            }

        } catch (Exception e) {
            log.error("Error during job check for instance {}: {}", instance.getId(), e.getMessage(), e);
            completeRun(run, "ERROR", e.getMessage(), null);
        }
    }

    public void runRestoreTests() {
        if (!config.getRestoreTest().isEnabled()) {
            log.info("Restore tests disabled");
            return;
        }
        restoreTestMonitor.runAll();
    }

    private MonitorRun startRun(String managerId, String instanceId,
                                  String instanceName, RunType runType) {
        MonitorRun run = new MonitorRun();
        run.setManagerId(managerId);
        run.setInstanceId(instanceId);
        run.setInstanceName(instanceName);
        run.setRunType(runType.name());
        run.setStartedAt(Instant.now());
        run.setStatus("RUNNING");
        return monitorRunRepo.save(run);
    }

    private void completeRun(MonitorRun run, String status, String errorMessage,
                               Map<String, Object> details) {
        run.setFinishedAt(Instant.now());
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        run.setDetails(details);
        monitorRunRepo.save(run);
    }

    private Map<String, Object> buildJobDetails(BackupJob job) {
        if (job == null) return null;
        Map<String, Object> details = new HashMap<>();
        details.put("jobId", job.getIdAsString());
        details.put("status", job.getStatus() != null ? job.getStatus().name() : null);
        details.put("endDate", job.getEndDate() != null ? job.getEndDate().toString() : null);
        return details;
    }
}
