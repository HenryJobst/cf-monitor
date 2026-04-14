package de.example.backupmonitor.monitor;

import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.s3.S3CheckResult;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobCheckResult {

    private boolean success;
    private BackupJob job;
    private String message;
    private S3CheckResult s3CheckResult;

    public static JobCheckResult success(BackupJob job) {
        return JobCheckResult.builder().success(true).job(job).build();
    }

    public static JobCheckResult failed(String message) {
        return JobCheckResult.builder().success(false).message(message).build();
    }

    public static JobCheckResult noJob() {
        return JobCheckResult.builder().success(false).message("No backup job found").build();
    }
}
