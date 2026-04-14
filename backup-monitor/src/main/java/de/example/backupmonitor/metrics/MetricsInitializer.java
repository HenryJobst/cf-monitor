package de.example.backupmonitor.metrics;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.persistence.MonitorRun;
import de.example.backupmonitor.persistence.MonitorRunRepository;
import de.example.backupmonitor.persistence.RunType;
import de.example.backupmonitor.s3.S3CheckResultEntity;
import de.example.backupmonitor.s3.S3CheckResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetricsInitializer {

    private final MonitorRunRepository monitorRunRepo;
    private final S3CheckResultRepository s3CheckResultRepo;
    private final MetricsPublisher metricsPublisher;
    private final MonitoringConfig config;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeMetricsFromDb() {
        log.info("Initializing metrics from DB on startup");

        // Letzte Monitor-Run-Timestamps je Manager wiederherstellen
        List<MonitorRun> latestRuns = monitorRunRepo.findLatestPerInstance();
        for (MonitorRun run : latestRuns) {
            if (RunType.JOB_CHECK.name().equals(run.getRunType()) && run.getStartedAt() != null) {
                metricsPublisher.recordMonitorRun(run.getManagerId(), run.getStartedAt());
            }
        }

        // Letzte S3-Check-Ergebnisse je Instance wiederherstellen
        for (MonitoringConfig.ManagerConfig manager : config.getManagers()) {
            for (MonitoringConfig.ServiceInstanceConfig instance : manager.getInstances()) {
                s3CheckResultRepo.findLatestForInstance(instance.getId()).ifPresent(latest -> {
                    metricsPublisher.recordS3CheckResult(
                            latest.getManagerId(),
                            latest.getInstanceId(),
                            instance.getName(),
                            toS3CheckResult(latest));
                });
            }
        }

        log.info("Metrics initialization from DB completed");
    }

    private de.example.backupmonitor.s3.S3CheckResult toS3CheckResult(S3CheckResultEntity e) {
        return de.example.backupmonitor.s3.S3CheckResult.builder()
                .managerId(e.getManagerId())
                .instanceId(e.getInstanceId())
                .backupJobId(e.getBackupJobId())
                .filename(e.getFilename())
                .bucket(e.getBucket())
                .exists(e.isExists())
                .sizeExpectedBytes(e.getSizeExpectedBytes() != null ? e.getSizeExpectedBytes() : 0L)
                .sizeActualBytes(e.getSizeActualBytes() != null ? e.getSizeActualBytes() : 0L)
                .sizeMatchWithinTolerance(e.isSizeMatchWithinTolerance())
                .sizeDeviationPercent(e.getSizeDeviationPercent() != null
                        ? e.getSizeDeviationPercent().doubleValue() : 0.0)
                .accessible(e.isAccessible())
                .magicBytesValid(e.isMagicBytesValid())
                .allPassed(e.isAllPassed())
                .checkedAt(e.getCheckedAt())
                .build();
    }
}
