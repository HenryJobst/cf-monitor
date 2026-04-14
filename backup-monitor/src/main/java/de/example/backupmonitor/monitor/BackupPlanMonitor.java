package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.model.BackupPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackupPlanMonitor {

    private final BackupManagerClient managerClient;

    public PlanCheckResult checkPlan(String managerId, String instanceId) {
        Optional<BackupPlan> planOpt = managerClient.getBackupPlan(managerId, instanceId);

        if (planOpt.isEmpty()) {
            log.warn("No backup plan found for instance {}", instanceId);
            return PlanCheckResult.failed("No backup plan found");
        }

        BackupPlan plan = planOpt.get();

        if (plan.isPaused()) {
            log.warn("Backup plan is paused for instance {}", instanceId);
            return PlanCheckResult.failed("Backup plan is paused");
        }

        log.debug("Backup plan OK for instance {}", instanceId);
        return PlanCheckResult.ok(plan);
    }
}
