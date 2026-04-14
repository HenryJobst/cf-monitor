package de.example.backupmonitor.persistence;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.s3.S3CheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupJobTest {

    @Mock
    private MonitorRunRepository monitorRunRepo;

    @Mock
    private S3CheckResultRepository s3CheckResultRepo;

    private RetentionCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        MonitoringConfig config = new MonitoringConfig();
        config.getRetention().setJobCheckEntries(20);
        config.getRetention().setRestoreTestEntries(5);
        config.getRetention().setS3CheckEntries(15);
        cleanupJob = new RetentionCleanupJob(monitorRunRepo, s3CheckResultRepo, config);
    }

    @Test
    void cleanup_callsAllRepositoriesWithConfiguredValues() {
        cleanupJob.cleanup();

        verify(monitorRunRepo).deleteOldEntriesPerInstanceAndType(RunType.JOB_CHECK.name(), 20);
        verify(monitorRunRepo).deleteOldEntriesPerInstanceAndType(RunType.RESTORE_TEST.name(), 5);
        verify(s3CheckResultRepo).deleteOldEntriesPerInstance(15);
    }
}
