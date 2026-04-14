package de.example.backupmonitor.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "monitor_run")
@Getter
@Setter
public class MonitorRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    @Column(name = "instance_id", nullable = false, length = 128)
    private String instanceId;

    @Column(name = "instance_name", length = 256)
    private String instanceName;

    @Column(name = "run_type", nullable = false, length = 32)
    private String runType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
