package de.example.backupmonitor.metrics;

import com.google.common.util.concurrent.AtomicDouble;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import de.example.backupmonitor.monitor.RestoreTestResult;
import de.example.backupmonitor.s3.S3CheckResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MetricsPublisher {

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, AtomicDouble> gaugeValues = new ConcurrentHashMap<>();

    private Tags instanceTags(String managerId, String instanceId, String instanceName) {
        return Tags.of(
                "manager_id", managerId,
                "instance_id", instanceId,
                "instance_name", instanceName != null ? instanceName : instanceId);
    }

    private AtomicDouble getOrRegisterGauge(String metricName, Tags tags, String description) {
        String key = metricName + ":" + tags.stream()
                .map(t -> t.getKey() + "=" + t.getValue())
                .collect(Collectors.joining(":"));

        return gaugeValues.computeIfAbsent(key, k -> {
            AtomicDouble value = new AtomicDouble(0.0);
            Gauge.builder(metricName, value, AtomicDouble::get)
                    .tags(tags)
                    .description(description)
                    .register(registry);
            return value;
        });
    }

    public void recordPlanStatus(String managerId, String instanceId,
                                  String instanceName, BackupPlan plan) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        getOrRegisterGauge(MetricNames.PLAN_ACTIVE, tags,
                "1 = plan exists and active, 0 = missing or paused")
                .set(plan != null && !plan.isPaused() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.PLAN_PAUSED, tags,
                "1 = plan is paused")
                .set(plan != null && plan.isPaused() ? 1.0 : 0.0);
    }

    public void recordJobResult(String managerId, String instanceId,
                                 String instanceName, BackupJob job) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        getOrRegisterGauge(MetricNames.JOB_LAST_STATUS, tags,
                "1=SUCCEEDED, 0=otherwise")
                .set(job != null && JobStatus.SUCCEEDED == job.getStatus() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.JOB_LAST_AGE_HOURS, tags,
                "Age of last backup in hours")
                .set(job != null && job.getEndDate() != null
                        ? Duration.between(job.getEndDate(), Instant.now()).toHours()
                        : -1.0);
        getOrRegisterGauge(MetricNames.JOB_LAST_FILESIZE, tags,
                "Size of last backup file in bytes")
                .set(resolveFilesize(job));
        getOrRegisterGauge(MetricNames.JOB_LAST_DURATION_MS, tags,
                "Total execution time of last backup job in milliseconds")
                .set(resolveDurationMs(job));
    }

    public void recordRestoreResult(String managerId, String instanceId,
                                     String instanceName, RestoreTestResult result) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        double statusVal = switch (result.status()) {
            case OK -> 1.0;
            case SKIPPED -> -1.0;
            case NO_RESOURCES -> -2.0;
            default -> 0.0;
        };
        getOrRegisterGauge(MetricNames.RESTORE_LAST_STATUS, tags,
                "1=ok, 0=failed, -1=skipped, -2=no_resources")
                .set(statusVal);
        getOrRegisterGauge(MetricNames.RESTORE_LAST_DURATION_SEC, tags,
                "Duration of last restore test in seconds")
                .set(result.durationSeconds());
        getOrRegisterGauge(MetricNames.RESTORE_VALIDATION_PASSED, tags,
                "1 = all validation queries passed")
                .set(result.allValidationsPassed() ? 1.0 : 0.0);
    }

    public void recordS3CheckResult(String managerId, String instanceId,
                                     String instanceName, S3CheckResult result) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);

        getOrRegisterGauge(MetricNames.S3_FILE_EXISTS, tags,
                "1 = backup file exists in S3")
                .set(result.isExists() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.S3_SIZE_MATCH, tags,
                "1 = file size within configured tolerance")
                .set(result.isSizeMatch() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.S3_ACCESSIBLE, tags,
                "1 = file bytes downloadable via range request")
                .set(result.isAccessible() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.S3_MAGIC_BYTES_VALID, tags,
                "1 = file has valid archive signature (gzip or tar)")
                .set(result.isMagicBytesValid() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.S3_ALL_CHECKS_PASSED, tags,
                "1 = all S3 checks passed (exists, size, accessible, magic bytes)")
                .set(result.isAllPassed() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.S3_FILE_SIZE_BYTES, tags,
                "Actual file size in S3 in bytes")
                .set(result.getSizeActualBytes());
    }

    public void incrementJobSuccess(String managerId, String instanceId, String instanceName) {
        registry.counter(MetricNames.JOB_SUCCESS_TOTAL,
                instanceTags(managerId, instanceId, instanceName)).increment();
    }

    public void incrementJobFailure(String managerId, String instanceId, String instanceName) {
        registry.counter(MetricNames.JOB_FAILURE_TOTAL,
                instanceTags(managerId, instanceId, instanceName)).increment();
    }

    public void recordMonitorRun(String managerId, Instant runTime) {
        gaugeValues.computeIfAbsent(
                MetricNames.MONITOR_LAST_RUN + ":" + managerId, k -> {
                    AtomicDouble v = new AtomicDouble(runTime.getEpochSecond());
                    Gauge.builder(MetricNames.MONITOR_LAST_RUN, v, AtomicDouble::get)
                            .tag("manager_id", managerId)
                            .description("Timestamp of last monitor run")
                            .register(registry);
                    return v;
                }).set(runTime.getEpochSecond());
    }

    private double resolveFilesize(BackupJob job) {
        if (job == null || job.getAgentExecutionReponses() == null) return 0.0;
        return job.getAgentExecutionReponses().values().stream()
                .mapToLong(r -> r.getFilesizeBytes() != null ? r.getFilesizeBytes() : 0L)
                .sum();
    }

    private double resolveDurationMs(BackupJob job) {
        if (job == null || job.getAgentExecutionReponses() == null) return -1.0;
        return job.getAgentExecutionReponses().values().stream()
                .mapToLong(r -> r.getExecutionTimeMs() != null ? r.getExecutionTimeMs() : 0L)
                .sum();
    }
}
