package de.example.backupmonitor.persistence;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.s3.S3CheckResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetentionCleanupJob {

    private final MonitorRunRepository monitorRunRepo;
    private final S3CheckResultRepository s3CheckResultRepo;
    private final MonitoringConfig config;

    @Transactional
    public void cleanup() {
        int jobCheckN = config.getRetention().getJobCheckEntries();
        int restoreTestN = config.getRetention().getRestoreTestEntries();
        int s3CheckN = config.getRetention().getS3CheckEntries();

        log.debug("Running retention cleanup: jobCheck={}, restoreTest={}, s3Check={}",
                jobCheckN, restoreTestN, s3CheckN);

        monitorRunRepo.deleteOldEntriesPerInstanceAndType(RunType.JOB_CHECK.name(), jobCheckN);
        monitorRunRepo.deleteOldEntriesPerInstanceAndType(RunType.RESTORE_TEST.name(), restoreTestN);
        s3CheckResultRepo.deleteOldEntriesPerInstance(s3CheckN);

        log.debug("Retention cleanup completed");
    }
}
