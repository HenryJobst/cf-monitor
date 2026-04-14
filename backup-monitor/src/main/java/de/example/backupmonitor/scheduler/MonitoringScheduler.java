package de.example.backupmonitor.scheduler;

import de.example.backupmonitor.monitor.MonitoringOrchestrator;
import de.example.backupmonitor.sandbox.OrphanedSandboxCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonitoringScheduler {

    private final MonitoringOrchestrator orchestrator;
    private final OrphanedSandboxCleaner sandboxCleaner;

    @Scheduled(cron = "${backup-monitor.scheduling.job-check-cron}")
    @SchedulerLock(name = "job_check", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void runJobCheck() {
        log.info("Starting job check (ShedLock acquired)");
        orchestrator.runJobChecks();
    }

    @Scheduled(cron = "${backup-monitor.restore-test.cron}")
    @SchedulerLock(name = "restore_test", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
    public void runRestoreTest() {
        log.info("Starting restore test (ShedLock acquired)");
        orchestrator.runRestoreTests();
    }

    @Scheduled(cron = "${backup-monitor.scheduling.orphan-cleanup-cron}")
    @SchedulerLock(name = "orphan_cleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runOrphanCleanup() {
        log.info("Starting orphaned sandbox cleanup");
        sandboxCleaner.cleanupOrphans();
    }
}
