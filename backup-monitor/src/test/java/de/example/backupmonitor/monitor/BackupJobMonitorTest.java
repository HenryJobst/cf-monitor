package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupJobMonitorTest {

    @Mock
    private BackupManagerClient managerClient;

    @InjectMocks
    private BackupJobMonitor jobMonitor;

    @Test
    void checkLatestJob_succeededJob_returnsSuccess() {
        BackupJob job = new BackupJob();
        job.setId("job-1");
        job.setStatus(JobStatus.SUCCEEDED);
        when(managerClient.getLatestJob("mgr", "inst")).thenReturn(Optional.of(job));

        JobCheckResult result = jobMonitor.checkLatestJob("mgr", "inst", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getJob()).isEqualTo(job);
    }

    @Test
    void checkLatestJob_noJob_returnsFailed() {
        when(managerClient.getLatestJob("mgr", "inst")).thenReturn(Optional.empty());

        JobCheckResult result = jobMonitor.checkLatestJob("mgr", "inst", null);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void checkLatestJob_failedJob_returnsFailed() {
        BackupJob job = new BackupJob();
        job.setId("job-1");
        job.setStatus(JobStatus.FAILED);
        when(managerClient.getLatestJob("mgr", "inst")).thenReturn(Optional.of(job));

        JobCheckResult result = jobMonitor.checkLatestJob("mgr", "inst", null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("FAILED");
    }
}
