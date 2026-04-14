package de.example.backupmonitor.persistence;

import de.example.backupmonitor.monitor.RestoreTestResult;
import de.example.backupmonitor.validation.QueryCheckResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Entity
@Table(name = "restore_test_result")
@Getter
@Setter
public class RestoreTestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_run_id", nullable = false)
    private Long monitorRunId;

    @Column(name = "instance_id", nullable = false, length = 128)
    private String instanceId;

    @Column(name = "backup_job_id", length = 256)
    private String backupJobId;

    @Column(name = "backup_file_name", length = 512)
    private String backupFileName;

    @Column(name = "sandbox_mode", length = 32)
    private String sandboxMode;

    @Column(name = "restore_status", length = 32)
    private String restoreStatus;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_results", columnDefinition = "jsonb")
    private List<Map<String, Object>> validationResults;

    @Column(name = "all_validations_passed")
    private Boolean allValidationsPassed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public static RestoreTestResultEntity from(Long monitorRunId, RestoreTestResult result,
                                                String backupJobId, String filename,
                                                String sandboxMode) {
        RestoreTestResultEntity entity = new RestoreTestResultEntity();
        entity.setMonitorRunId(monitorRunId);
        entity.setInstanceId(result.instanceId());
        entity.setBackupJobId(backupJobId);
        entity.setBackupFileName(filename);
        entity.setSandboxMode(sandboxMode);
        entity.setRestoreStatus(result.status().name());
        entity.setDurationSeconds((int) result.durationSeconds());
        entity.setAllValidationsPassed(result.allValidationsPassed());
        entity.setValidationResults(result.validationResults().stream()
                .map(q -> Map.<String, Object>of(
                        "description", q.description(),
                        "passed", q.passed(),
                        "result", q.result(),
                        "minResult", q.minResult()))
                .collect(Collectors.toList()));
        return entity;
    }
}
