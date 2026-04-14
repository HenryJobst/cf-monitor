package de.example.backupmonitor.s3;

import de.example.backupmonitor.metrics.MetricsPublisher;
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

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class S3VerificationService {

    private static final byte[] GZIP_MAGIC = {0x1F, (byte) 0x8B};

    private final S3ClientFactory clientFactory;
    private final S3CheckResultRepository repository;
    private final MetricsPublisher metrics;

    @Value("${backup-monitor.s3-verification.size-tolerance-percent:5}")
    private int sizeTolerance;

    @Value("${backup-monitor.s3-verification.accessibility-check-bytes:1024}")
    private int accessibilityBytes;

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
                double deviation = Math.abs(s3Size - reportedSize)
                        / (double) reportedSize * 100.0;
                result.setSizeMatchWithinTolerance(deviation <= sizeTolerance);
                result.setSizeDeviationPercent(deviation);
            } else {
                result.setSizeMatchWithinTolerance(s3Size > 0);
                result.setSizeDeviationPercent(0.0);
            }

            // ── c) ACCESSIBLE ────────────────────────────────────────────
            byte[] firstBytes = downloadPartial(s3, dest.getBucket(), filename, accessibilityBytes);
            result.setAccessible(firstBytes != null && firstBytes.length > 0);

            if (!result.isAccessible()) {
                log.warn("S3 file not accessible: s3://{}/{}", dest.getBucket(), filename);
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── d) INTEGRITY (Magic Bytes) ───────────────────────────────
            if (firstBytes.length >= 2) {
                boolean gzipValid = firstBytes[0] == GZIP_MAGIC[0]
                        && firstBytes[1] == GZIP_MAGIC[1];
                result.setMagicBytesValid(gzipValid);
                if (!gzipValid) {
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
        if (job.getFiles() != null && !job.getFiles().isEmpty()) {
            return job.getFiles().values().iterator().next();
        }
        if (job.getAgentExecutionReponses() != null) {
            return job.getAgentExecutionReponses().values().stream()
                    .map(r -> r.getFilename())
                    .filter(f -> f != null && !f.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No filename in job " + job.getIdAsString()));
        }
        throw new IllegalStateException("No filename in job " + job.getIdAsString());
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
                        && result.isSizeMatchWithinTolerance()
                        && result.isAccessible()
                        && result.isMagicBytesValid());

        metrics.recordS3CheckResult(managerId, instanceId, instanceName, result);
        repository.save(S3CheckResultEntity.from(result));

        log.info("S3 verification for instance {}: allPassed={}, exists={}, accessible={}, "
                        + "sizeMatch={}, magicBytes={}",
                instanceId, result.isAllPassed(), result.isExists(),
                result.isAccessible(), result.isSizeMatchWithinTolerance(),
                result.isMagicBytesValid());

        return result;
    }
}
