package de.example.backupmonitor.sandbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sandbox_registry",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sandbox_registry_instance",
                columnNames = "instance_id"))
@Getter
@Setter
public class SandboxRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false, length = 128, unique = true)
    private String instanceId;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private SandboxMode mode;

    @Column(name = "cf_service_instance_name", length = 256)
    private String cfServiceInstanceName;

    @Column(name = "cf_service_instance_guid", length = 128)
    private String cfServiceInstanceGuid;

    @Column(name = "sandbox_host", nullable = false, length = 512)
    private String sandboxHost;

    @Column(name = "sandbox_port", nullable = false)
    private int sandboxPort;

    @Column(name = "sandbox_database", nullable = false, length = 256)
    private String sandboxDatabase;

    @Column(name = "sandbox_username", length = 256)
    private String sandboxUsername;

    @Column(name = "sandbox_password_enc", columnDefinition = "TEXT")
    private String sandboxPasswordEnc;

    @Column(name = "provisioned_at")
    private Instant provisionedAt;
}
