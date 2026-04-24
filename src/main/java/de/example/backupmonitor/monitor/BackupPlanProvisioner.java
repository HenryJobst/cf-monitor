package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.S3FileDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Legt automatisch einen Backup-Plan an, wenn für eine Service-Instanz keiner existiert
 * und im konfigurierten CF-Space ein S3-Service gefunden (oder angelegt) wird.
 *
 * <p>Aktivierung: {@code cf-backup-monitor.auto-provision.enabled=true} + {@code cf.space-guid} je Manager.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackupPlanProvisioner {

    private static final Duration S3_PROVISION_TIMEOUT = Duration.ofMinutes(10);

    private final MonitoringConfig config;
    private final CfApiClient cfApiClient;
    private final BackupManagerClient managerClient;

    /**
     * Versucht, einen Backup-Plan für die angegebene Instanz automatisch einzurichten.
     *
     * @return den neu erstellten BackupPlan, oder empty wenn kein Auto-Provisioning stattfand
     */
    public Optional<BackupPlan> tryProvision(String managerId, String instanceId) {
        MonitoringConfig.AutoProvisionProperties ap = config.getAutoProvision();
        if (!ap.isEnabled()) {
            return Optional.empty();
        }

        MonitoringConfig.ManagerConfig manager = findManager(managerId);
        String spaceGuid = manager.getCf().getSpaceGuid();
        if (spaceGuid == null || spaceGuid.isBlank()) {
            log.debug("Auto-provision: kein space-guid für Manager '{}' konfiguriert", managerId);
            return Optional.empty();
        }

        MonitoringConfig.ServiceInstanceConfig instance = findInstance(manager, instanceId);

        Optional<CfApiClient.S3ServiceCandidate> s3Opt =
                findOrCreateS3Service(managerId, manager, instance, spaceGuid, ap, instanceId);
        if (s3Opt.isEmpty()) return Optional.empty();
        CfApiClient.S3ServiceCandidate s3 = s3Opt.get();

        try {
            S3FileDestination destination = cfApiClient.getS3Credentials(
                    managerId, s3.guid(), s3.name());
            validateDestination(destination, s3.name());

            Optional<BackupPlan> plan = managerClient.createBackupPlan(
                    managerId, instanceId, ap.getBackupSchedule(),
                    ap.getRetentionStyle(), ap.getRetentionPeriod(),
                    ap.getTimezone(), destination);

            plan.ifPresentOrElse(
                    p -> log.info("Backup-Plan erfolgreich angelegt für Instanz {} "
                            + "(Schedule: {}, S3: {})", instanceId, ap.getBackupSchedule(), s3.name()),
                    () -> log.warn("Backup-Plan-Erstellung für Instanz {} lieferte leere Antwort", instanceId)
            );
            return plan;
        } catch (Exception e) {
            log.error("Auto-Provisioning fehlgeschlagen für Instanz {}: {}", instanceId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<CfApiClient.S3ServiceCandidate> findOrCreateS3Service(
            String managerId, MonitoringConfig.ManagerConfig manager,
            MonitoringConfig.ServiceInstanceConfig instance,
            String spaceGuid, MonitoringConfig.AutoProvisionProperties ap, String instanceId) {

        String s3Name = instance.getS3InstanceName();
        if (s3Name != null && !s3Name.isBlank()) {
            log.info("Kein Backup-Plan für Instanz {} – suche S3-Service '{}' in Space {}",
                    instanceId, s3Name, spaceGuid);
            Optional<CfApiClient.S3ServiceCandidate> found =
                    cfApiClient.findServiceInstanceByName(managerId, spaceGuid, s3Name);
            if (found.isPresent()) {
                log.info("S3-Service '{}' gefunden – richte Backup-Plan ein", s3Name);
                return found;
            }

            String s3Plan = instance.getS3ServicePlan();
            if (s3Plan == null || s3Plan.isBlank()) {
                log.info("S3-Service '{}' nicht gefunden und kein s3-service-plan konfiguriert – "
                        + "kein Auto-Provisioning", s3Name);
                return Optional.empty();
            }

            log.info("S3-Service '{}' nicht gefunden – lege an (Offering: '{}', Plan: '{}')",
                    s3Name, ap.getS3ServiceLabel(), s3Plan);
            cfApiClient.createServiceInstance(managerId, s3Name, ap.getS3ServiceLabel(), s3Plan, spaceGuid);
            cfApiClient.pollUntilReady(managerId, s3Name, S3_PROVISION_TIMEOUT);
            log.info("S3-Service '{}' bereit", s3Name);
            return cfApiClient.findServiceInstanceByName(managerId, spaceGuid, s3Name);
        }

        log.info("Kein Backup-Plan für Instanz {} – suche S3-Service (label='{}') in Space {}",
                instanceId, ap.getS3ServiceLabel(), spaceGuid);
        List<CfApiClient.S3ServiceCandidate> candidates =
                cfApiClient.findServiceInstancesByOffering(managerId, spaceGuid, ap.getS3ServiceLabel());
        if (candidates.isEmpty()) {
            log.info("Kein S3-Service (label='{}') in Space {} gefunden – kein Auto-Provisioning",
                    ap.getS3ServiceLabel(), spaceGuid);
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            log.warn("Mehrere S3-Services (label='{}') gefunden – verwende '{}'",
                    ap.getS3ServiceLabel(), candidates.get(0).name());
        }
        return Optional.of(candidates.get(0));
    }

    private void validateDestination(S3FileDestination dest, String serviceName) {
        if (dest.getAuthKey() == null || dest.getAuthSecret() == null) {
            throw new IllegalStateException(
                    "S3-Credentials (auth_key/auth_secret) fehlen in Service '" + serviceName + "'");
        }
        if (dest.getBucket() == null) {
            throw new IllegalStateException(
                    "S3-Bucket fehlt in Credentials von Service '" + serviceName + "'");
        }
    }

    private MonitoringConfig.ManagerConfig findManager(String managerId) {
        return config.getManagers().stream()
                .filter(m -> managerId.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Manager nicht gefunden: " + managerId));
    }

    private MonitoringConfig.ServiceInstanceConfig findInstance(
            MonitoringConfig.ManagerConfig manager, String instanceId) {
        return manager.getInstances().stream()
                .filter(i -> instanceId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Instanz nicht gefunden: " + instanceId));
    }
}
