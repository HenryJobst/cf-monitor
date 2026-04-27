package de.example.backupmonitor.s3;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "s3_check_result")
@Getter
@Setter
public class S3CheckResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    @Column(name = "instance_id", nullable = false, length = 128)
    private String instanceId;

    @Column(name = "backup_job_id", nullable = false, length = 256)
    private String backupJobId;

    @Column(name = "filename", length = 512)
    private String filename;

    @Column(name = "bucket", length = 256)
    private String bucket;

    @Column(name = "exists")
    private boolean exists;

    @Column(name = "size_expected_bytes")
    private Long sizeExpectedBytes;

    @Column(name = "size_actual_bytes")
    private Long sizeActualBytes;

    @Column(name = "size_match")
    private boolean sizeMatch;

    @Column(name = "compression")
    private boolean compression;

    @Column(name = "size_shrink_warning")
    private boolean sizeShrinkWarning;

    @Column(name = "accessible")
    private boolean accessible;

    @Column(name = "magic_bytes_valid")
    private boolean magicBytesValid;

    @Column(name = "all_passed")
    private boolean allPassed;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    public static S3CheckResultEntity from(S3CheckResult result) {
        S3CheckResultEntity entity = new S3CheckResultEntity();
        entity.setManagerId(result.getManagerId());
        entity.setInstanceId(result.getInstanceId());
        entity.setBackupJobId(result.getBackupJobId());
        entity.setFilename(result.getFilename());
        entity.setBucket(result.getBucket());
        entity.setExists(result.isExists());
        entity.setSizeExpectedBytes(result.getSizeExpectedBytes());
        entity.setSizeActualBytes(result.getSizeActualBytes());
        entity.setSizeMatch(result.isSizeMatch());
        entity.setCompression(result.isCompression());
        entity.setSizeShrinkWarning(result.isSizeShrinkWarning());
        entity.setAccessible(result.isAccessible());
        entity.setMagicBytesValid(result.isMagicBytesValid());
        entity.setAllPassed(result.isAllPassed());
        entity.setErrorMessage(result.getError());
        entity.setCheckedAt(result.getCheckedAt() != null ? result.getCheckedAt() : Instant.now());
        return entity;
    }
}
