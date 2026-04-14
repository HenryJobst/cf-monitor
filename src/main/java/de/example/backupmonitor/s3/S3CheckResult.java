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
    private boolean sizeMatchWithinTolerance;
    private double sizeDeviationPercent;

    // c) ACCESSIBLE
    private boolean accessible;

    // d) INTEGRITY
    private boolean magicBytesValid;

    // Gesamt
    private boolean allPassed;
    private String error;

    public boolean isS3Verified() {
        return exists && accessible && sizeMatchWithinTolerance;
    }
}
