package de.example.backupmonitor.s3;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Builder
@Data
public class S3CheckResult {

    private String managerId;
    private String instanceId;
    private String backupJobId;
    private String filename;
    private String bucket;
    private Instant checkedAt;

    // a) EXISTS
    private boolean exists;

    // b) SIZE
    private long sizeExpectedBytes;
    private long sizeActualBytes;
    private boolean sizeMatch;

    private boolean compression;

    // a0) BUCKET
    private boolean bucketAccessible;

    // b2) SIZE TREND
    private boolean sizeShrinkWarning;
    private boolean sizeGrowthWarning;

    // e) DURATION
    private long executionTimeMs;
    private boolean durationGrowthWarning;

    // f) FILE COUNT
    private Integer s3FileCount;
    private Integer expectedFileCount;

    // c) ACCESSIBLE
    private boolean accessible;

    // d) INTEGRITY
    private boolean magicBytesValid;

    // Gesamt
    private boolean allPassed;
    private String error;

    public boolean isS3Verified() {
        return exists && accessible && sizeMatch;
    }
}
