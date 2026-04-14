package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.model.BackupPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupPlanMonitorTest {

    @Mock
    private BackupManagerClient managerClient;

    @InjectMocks
    private BackupPlanMonitor planMonitor;

    @Test
    void checkPlan_noPlan_returnsFailed() {
        when(managerClient.getBackupPlan("mgr", "inst")).thenReturn(Optional.empty());

        PlanCheckResult result = planMonitor.checkPlan("mgr", "inst");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("No backup plan");
    }

    @Test
    void checkPlan_pausedPlan_returnsFailed() {
        BackupPlan plan = new BackupPlan();
        plan.setPaused(true);
        when(managerClient.getBackupPlan("mgr", "inst")).thenReturn(Optional.of(plan));

        PlanCheckResult result = planMonitor.checkPlan("mgr", "inst");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("paused");
    }

    @Test
    void checkPlan_activePlan_returnsOk() {
        BackupPlan plan = new BackupPlan();
        plan.setPaused(false);
        plan.setActive(true);
        when(managerClient.getBackupPlan("mgr", "inst")).thenReturn(Optional.of(plan));

        PlanCheckResult result = planMonitor.checkPlan("mgr", "inst");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getPlan()).isSameAs(plan);
    }
}
