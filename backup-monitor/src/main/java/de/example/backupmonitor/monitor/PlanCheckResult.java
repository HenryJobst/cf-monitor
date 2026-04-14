package de.example.backupmonitor.monitor;

import de.example.backupmonitor.model.BackupPlan;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlanCheckResult {

    private boolean ok;
    private BackupPlan plan;
    private String message;

    public static PlanCheckResult ok(BackupPlan plan) {
        return PlanCheckResult.builder().ok(true).plan(plan).build();
    }

    public static PlanCheckResult failed(String message) {
        return PlanCheckResult.builder().ok(false).message(message).build();
    }
}
