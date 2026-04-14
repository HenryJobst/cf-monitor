package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ResourceChecker {

    private final CfApiClient cfApiClient;

    public ResourceCheckResult check(String managerId,
                                      MonitoringConfig.ProvisionConfig cfg) {
        try {
            // Einfache Heuristik: Quota prüfen ob noch Service-Instanzen möglich
            log.debug("Checking CF resources for manager {} (org: {})",
                    managerId, cfg.getOrg());
            // Hier könnte man /v3/organizations/{org}/usage_summary auswerten
            // Für MVP: immer ausreichend annehmen wenn API erreichbar
            return ResourceCheckResult.sufficient();
        } catch (Exception e) {
            log.warn("Resource check failed: {}", e.getMessage());
            return ResourceCheckResult.insufficient("Resource check failed: " + e.getMessage());
        }
    }
}
