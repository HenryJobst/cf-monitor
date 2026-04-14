package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.S3FileDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Legt automatisch einen Backup-Plan an, wenn für eine Service-Instanz keiner existiert
 * und im konfigurierten CF-Space ein S3-Service gefunden wird.
 *
 * <p>Aktivierung: {@code backup-monitor.auto-provision.enabled=true} + {@code cf.space-guid} je Manager.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackupPlanProvisioner {

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

        log.info("Kein Backup-Plan für Instanz {} – suche S3-Service (label='{}') in Space {}",
                instanceId, ap.getS3ServiceLabel(), spaceGuid);

        List<CfApiClient.S3ServiceCandidate> candidates = cfApiClient.findServiceInstancesByOffering(
                managerId, spaceGuid, ap.getS3ServiceLabel());

        if (candidates.isEmpty()) {
            log.info("Kein S3-Service (label='{}') in Space {} gefunden – kein Auto-Provisioning",
                    ap.getS3ServiceLabel(), spaceGuid);
            return Optional.empty();
        }

        CfApiClient.S3ServiceCandidate s3 = candidates.get(0);
        if (candidates.size() > 1) {
            log.warn("Mehrere S3-Services gefunden – verwende '{}' (guid: {})", s3.name(), s3.guid());
        } else {
            log.info("S3-Service '{}' gefunden – richte Backup-Plan ein", s3.name());
        }

        try {
            S3FileDestination destination = cfApiClient.getS3Credentials(
                    managerId, s3.guid(), s3.name());
            validateDestination(destination, s3.name());

            Optional<BackupPlan> plan = managerClient.createBackupPlan(
                    managerId, instanceId, ap.getBackupSchedule(), destination);

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
}
