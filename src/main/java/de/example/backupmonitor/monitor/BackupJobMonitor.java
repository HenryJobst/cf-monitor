package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackupJobMonitor {

    private final BackupManagerClient managerClient;

    public JobCheckResult checkLatestJob(String managerId, String instanceId, BackupPlan plan) {
        Optional<BackupJob> jobOpt = managerClient.getLatestJob(managerId, instanceId);

        if (jobOpt.isEmpty()) {
            log.warn("No backup job found for instance {}", instanceId);
            return JobCheckResult.noJob();
        }

        BackupJob job = jobOpt.get();

        if (job.getStatus() != JobStatus.SUCCEEDED) {
            log.warn("Latest backup job for instance {} has status {}", instanceId, job.getStatus());
            return JobCheckResult.failed("Job status: " + job.getStatus());
        }

        log.debug("Backup job OK for instance {} (job: {})", instanceId, job.getIdAsString());
        return JobCheckResult.success(job);
    }
}
