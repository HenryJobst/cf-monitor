package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanedSandboxCleaner {

    private final SandboxRegistryRepository registryRepo;
    private final CfApiClient cfApiClient;
    private final MonitoringConfig config;

    public void cleanupOrphans() {
        Set<String> configuredInstanceIds = config.getManagers().stream()
                .flatMap(m -> m.getInstances().stream())
                .map(MonitoringConfig.ServiceInstanceConfig::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        List<SandboxRegistryEntry> orphans = registryRepo.findAll().stream()
                .filter(e -> !configuredInstanceIds.contains(e.getInstanceId()))
                .filter(e -> e.getMode() == SandboxMode.PROVISION)
                .toList();

        if (orphans.isEmpty()) {
            log.debug("No orphaned sandboxes found");
            return;
        }

        log.info("Found {} orphaned sandbox(es) to deprovision", orphans.size());
        for (SandboxRegistryEntry orphan : orphans) {
            try {
                log.info("Deprovisioning orphaned sandbox {} (CF instance: {})",
                        orphan.getInstanceId(), orphan.getCfServiceInstanceName());
                cfApiClient.deleteServiceInstance(
                        orphan.getManagerId(),
                        orphan.getCfServiceInstanceGuid());
                registryRepo.delete(orphan);
                log.info("Successfully deprovisioned orphaned sandbox {}", orphan.getInstanceId());
            } catch (Exception e) {
                log.error("Failed to deprovision orphaned sandbox {}: {}",
                        orphan.getInstanceId(), e.getMessage());
            }
        }
    }
}
