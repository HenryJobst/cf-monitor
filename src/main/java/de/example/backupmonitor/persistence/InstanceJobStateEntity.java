package de.example.backupmonitor.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "instance_job_state")
@Getter
@Setter
public class InstanceJobStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    @Column(name = "instance_id", nullable = false, unique = true, length = 128)
    private String instanceId;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "has_ever_succeeded", nullable = false)
    private boolean hasEverSucceeded;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
