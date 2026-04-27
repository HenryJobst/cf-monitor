package de.example.backupmonitor.s3;

import de.example.backupmonitor.metrics.MetricsPublisher;
import de.example.backupmonitor.model.AgentExecutionResponse;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.S3FileDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class S3VerificationService {

    private static final byte[] GZIP_MAGIC = {0x1F, (byte) 0x8B};
    private static final byte[] TAR_USTAR  = {'u', 's', 't', 'a', 'r'};
    private static final int    TAR_USTAR_OFFSET = 257;
    private static final int    TAR_MIN_BYTES    = TAR_USTAR_OFFSET + TAR_USTAR.length;

    private final S3ClientFactory clientFactory;
    private final S3CheckResultRepository repository;
    private final MetricsPublisher metrics;

    @Value("${cf-backup-monitor.s3-verification.accessibility-check-bytes:1024}")
    private int accessibilityBytes;

    @Value("${cf-backup-monitor.s3-verification.shrink-warning-threshold-percent:20}")
    private int shrinkWarningThresholdPercent;

    @Value("${cf-backup-monitor.s3-verification.growth-warning-threshold-percent:50}")
    private int growthWarningThresholdPercent;

    @Value("${cf-backup-monitor.s3-verification.duration-growth-threshold-percent:50}")
    private int durationGrowthThresholdPercent;

    public S3CheckResult verify(String managerId, String instanceId,
                                 String instanceName, BackupJob job) {
        if (!(job.getDestination() instanceof S3FileDestination dest)) {
            log.warn("Job {} has non-S3 destination, skipping S3 verification",
                    job.getIdAsString());
            return S3CheckResult.builder()
                    .managerId(managerId).instanceId(instanceId)
                    .backupJobId(job.getIdAsString())
                    .checkedAt(Instant.now())
                    .error("Non-S3 destination")
                    .build();
        }

        String filename = resolveFilename(job);
        S3CheckResult result = S3CheckResult.builder()
                .managerId(managerId)
                .instanceId(instanceId)
                .backupJobId(job.getIdAsString())
                .filename(filename)
                .bucket(dest.getBucket())
                .checkedAt(Instant.now())
                .build();

        try (S3Client s3 = clientFactory.createClient(dest)) {

            // ── a0) BUCKET ACCESSIBLE ─────────────────────────────────────
            result.setBucketAccessible(checkBucketAccessible(s3, dest.getBucket()));
            if (!result.isBucketAccessible()) {
                log.warn("S3 bucket not accessible: {}", dest.getBucket());
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── a) EXISTS ────────────────────────────────────────────────
            HeadObjectResponse head = checkExists(s3, dest.getBucket(), filename);
            result.setExists(head != null);

            if (!result.isExists()) {
                log.warn("S3 file not found: s3://{}/{}", dest.getBucket(), filename);
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── b) SIZE ──────────────────────────────────────────────────
            long s3Size = head.contentLength();
            long reportedSize = resolveReportedSize(job);
            result.setSizeActualBytes(s3Size);
            result.setSizeExpectedBytes(reportedSize);

            if (reportedSize > 0) {
                result.setSizeMatch(s3Size == reportedSize);
            } else {
                result.setSizeMatch(s3Size > 0);
            }

            // ── b2) SIZE TREND ───────────────────────────────────────────
            boolean compression = job.getBackupPlan() != null && job.getBackupPlan().isCompression();
            result.setCompression(compression);
            checkSizeTrend(result, instanceId, s3Size, compression);

            // ── e) DURATION ──────────────────────────────────────────────
            long execTimeMs = resolveExecutionTimeMs(job);
            result.setExecutionTimeMs(execTimeMs);
            checkDurationGrowth(result, instanceId, execTimeMs, compression);

            // ── f) FILE COUNT ─────────────────────────────────────────────
            String prefix = job.getBackupPlan() != null && job.getBackupPlan().getIdAsString() != null
                    ? job.getBackupPlan().getIdAsString() + "/" : "";
            result.setS3FileCount(listObjectCount(s3, dest.getBucket(), prefix));
            result.setExpectedFileCount(resolveExpectedFileCount(job.getBackupPlan()));

            // ── c) ACCESSIBLE ────────────────────────────────────────────
            int bytesToFetch = Math.max(accessibilityBytes, TAR_MIN_BYTES);
            byte[] firstBytes = downloadPartial(s3, dest.getBucket(), filename, bytesToFetch);
            result.setAccessible(firstBytes != null && firstBytes.length > 0);

            if (!result.isAccessible()) {
                log.warn("S3 file not accessible: s3://{}/{}", dest.getBucket(), filename);
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── d) INTEGRITY (Magic Bytes) ───────────────────────────────
            if (firstBytes.length >= 2) {
                boolean valid = isGzip(firstBytes) || isTar(firstBytes);
                result.setMagicBytesValid(valid);
                if (!valid) {
                    log.warn("S3 file has unexpected magic bytes: {} {}",
                            String.format("0x%02X", firstBytes[0]),
                            String.format("0x%02X", firstBytes[1]));
                }
            }

        } catch (NoSuchKeyException e) {
            result.setExists(false);
            log.warn("S3 file not found (NoSuchKey): {}", filename);
        } catch (Exception e) {
            result.setError(e.getMessage());
            log.error("S3 verification failed for job {}: {}",
                    job.getIdAsString(), e.getMessage());
        }

        return finalize(result, managerId, instanceId, instanceName);
    }

    private void checkSizeTrend(S3CheckResult result, String instanceId,
                                 long currentSize, boolean compression) {
        if (currentSize <= 0) return;
        repository.findLatestPassedForInstance(instanceId).ifPresent(prev -> {
            if (prev.getSizeActualBytes() == null || prev.getSizeActualBytes() <= 0) return;
            if (prev.isCompression() != compression) return;
            long prevSize = prev.getSizeActualBytes();
            double changePct = (double) (prevSize - currentSize) / prevSize * 100.0;
            if (changePct >= shrinkWarningThresholdPercent) {
                result.setSizeShrinkWarning(true);
                log.warn("Backup file shrank by {:.1f}% (prev={} B, now={} B) for instance {}",
                        changePct, prevSize, currentSize, instanceId);
            } else if (-changePct >= growthWarningThresholdPercent) {
                result.setSizeGrowthWarning(true);
                log.warn("Backup file grew by {:.1f}% (prev={} B, now={} B) for instance {}",
                        -changePct, prevSize, currentSize, instanceId);
            }
        });
    }

    private void checkDurationGrowth(S3CheckResult result, String instanceId,
                                      long currentMs, boolean compression) {
        if (currentMs <= 0) return;
        repository.findLatestPassedForInstance(instanceId).ifPresent(prev -> {
            if (prev.getExecutionTimeMs() == null || prev.getExecutionTimeMs() <= 0) return;
            if (prev.isCompression() != compression) return;
            long prevMs = prev.getExecutionTimeMs();
            double growthPct = (double) (currentMs - prevMs) / prevMs * 100.0;
            if (growthPct >= durationGrowthThresholdPercent) {
                result.setDurationGrowthWarning(true);
                log.warn("Backup duration grew by {:.1f}% (prev={} ms, now={} ms) for instance {}",
                        growthPct, prevMs, currentMs, instanceId);
            }
        });
    }

    private long resolveExecutionTimeMs(BackupJob job) {
        if (job.getAgentExecutionReponses() == null) return 0L;
        return job.getAgentExecutionReponses().values().stream()
                .mapToLong(r -> r.getExecutionTimeMs() != null ? r.getExecutionTimeMs() : 0L)
                .sum();
    }

    private boolean checkBucketAccessible(S3Client s3, String bucket) {
        try {
            s3.headBucket(r -> r.bucket(bucket));
            return true;
        } catch (Exception e) {
            log.warn("Bucket head check failed for {}: {}", bucket, e.getMessage());
            return false;
        }
    }

    private Integer listObjectCount(S3Client s3, String bucket, String prefix) {
        try {
            int count = 0;
            var paginator = s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix));
            for (var page : paginator) {
                count += page.contents().size();
            }
            return count;
        } catch (Exception e) {
            log.warn("Failed to list objects in s3://{}/{}: {}", bucket, prefix, e.getMessage());
            return null;
        }
    }

    private Integer resolveExpectedFileCount(de.example.backupmonitor.model.BackupPlan plan) {
        if (plan == null || plan.getRetentionStyle() == null || plan.getRetentionPeriod() == null) return null;
        return switch (plan.getRetentionStyle().toUpperCase()) {
            case "FILES" -> plan.getRetentionPeriod();
            case "DAYS" -> {
                Duration interval = resolveIntervalFromPlan(plan);
                if (interval == null) yield null;
                double intervalHours = interval.toHours();
                if (intervalHours <= 0) yield null;
                yield (int) Math.round(plan.getRetentionPeriod() * 24.0 / intervalHours);
            }
            case "HOURS" -> {
                Duration interval = resolveIntervalFromPlan(plan);
                if (interval == null) yield null;
                double intervalHours = interval.toHours();
                if (intervalHours <= 0) yield null;
                yield (int) Math.round(plan.getRetentionPeriod() / intervalHours);
            }
            default -> null;
        };
    }

    private Duration resolveIntervalFromPlan(de.example.backupmonitor.model.BackupPlan plan) {
        if (plan.getFrequency() == null) return null;
        try {
            return MetricsPublisher.parseCronInterval(plan.getFrequency());
        } catch (Exception e) {
            log.debug("Could not parse cron '{}': {}", plan.getFrequency(), e.getMessage());
            return null;
        }
    }

    private boolean isGzip(byte[] bytes) {
        return bytes.length >= 2
                && bytes[0] == GZIP_MAGIC[0]
                && bytes[1] == GZIP_MAGIC[1];
    }

    private boolean isTar(byte[] bytes) {
        if (bytes.length < TAR_MIN_BYTES) return false;
        for (int i = 0; i < TAR_USTAR.length; i++) {
            if (bytes[TAR_USTAR_OFFSET + i] != TAR_USTAR[i]) return false;
        }
        return true;
    }

    private HeadObjectResponse checkExists(S3Client s3, String bucket, String key) {
        try {
            return s3.headObject(r -> r.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private byte[] downloadPartial(S3Client s3, String bucket, String key, int bytes) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=0-" + (bytes - 1))
                    .build();
            return s3.getObject(request).readAllBytes();
        } catch (Exception e) {
            log.debug("Partial download failed for s3://{}/{}: {}", bucket, key, e.getMessage());
            return null;
        }
    }

    private String resolveFilename(BackupJob job) {
        String filename = extractRawFilename(job);
        return prependPlanId(filename, job);
    }

    private String extractRawFilename(BackupJob job) {
        if (job.getFiles() != null && !job.getFiles().isEmpty()) {
            return job.getFiles().values().iterator().next();
        }
        if (job.getAgentExecutionReponses() != null) {
            return job.getAgentExecutionReponses().values().stream()
                    .map(AgentExecutionResponse::getFilename)
                    .filter(f -> f != null && !f.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No filename in job " + job.getIdAsString()));
        }
        throw new IllegalStateException("No filename in job " + job.getIdAsString());
    }

    private String prependPlanId(String filename, BackupJob job) {
        if (filename.contains("/")) return filename;
        if (job.getBackupPlan() == null) return filename;
        String planId = job.getBackupPlan().getIdAsString();
        if (planId == null || planId.isBlank()) return filename;
        return planId + "/" + filename;
    }

    private long resolveReportedSize(BackupJob job) {
        if (job.getFilesize() != null) return job.getFilesize();
        if (job.getAgentExecutionReponses() == null) return 0L;
        return job.getAgentExecutionReponses().values().stream()
                .mapToLong(r -> r.getFilesizeBytes() != null ? r.getFilesizeBytes() : 0L)
                .sum();
    }

    private S3CheckResult finalize(S3CheckResult result, String managerId,
                                    String instanceId, String instanceName) {
        result.setAllPassed(
                result.isExists()
                        && result.isSizeMatch()
                        && result.isAccessible()
                        && result.isMagicBytesValid());

        metrics.recordS3CheckResult(managerId, instanceId, instanceName, result);
        repository.save(S3CheckResultEntity.from(result));

        log.info("S3 verification for instance {}: allPassed={}, exists={}, accessible={}, "
                        + "sizeMatch={}, magicBytes={}",
                instanceId, result.isAllPassed(), result.isExists(),
                result.isAccessible(), result.isSizeMatch(),
                result.isMagicBytesValid());

        return result;
    }
}
