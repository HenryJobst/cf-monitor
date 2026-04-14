package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.config.MonitoringConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxManager {

    private final SandboxRegistry registry;
    private final SandboxProvisioner provisioner;
    private final MonitoringConfig config;

    public SandboxConnection getSandbox(String instanceId) {
        Optional<SandboxConnection> existing = registry.findExisting(instanceId);
        if (existing.isPresent()) {
            log.debug("Using existing sandbox for instance {}", instanceId);
            return existing.get();
        }

        MonitoringConfig.SandboxConfig sandboxCfg = findSandboxConfig(instanceId);
        String mode = sandboxCfg.getMode();

        if ("existing".equalsIgnoreCase(mode)) {
            MonitoringConfig.ExistingSandboxConfig ex = sandboxCfg.getExisting();
            SandboxConnection conn = new SandboxConnection(
                    ex.getHost(), ex.getPort(), ex.getDatabase(),
                    ex.getUsername(), ex.getPassword(), null, false);
            return conn;
        }

        // mode=provision
        String managerId = findManagerIdForInstance(instanceId);
        return provisioner.provision(managerId, instanceId, sandboxCfg.getProvision());
    }

    private MonitoringConfig.SandboxConfig findSandboxConfig(String instanceId) {
        return config.getRestoreTest().getSandboxes().stream()
                .filter(s -> instanceId.equals(s.getInstanceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No sandbox config for instance: " + instanceId));
    }

    private String findManagerIdForInstance(String instanceId) {
        return config.getManagers().stream()
                .filter(m -> m.getInstances().stream()
                        .anyMatch(i -> instanceId.equals(i.getId())))
                .map(MonitoringConfig.ManagerConfig::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No manager for instance: " + instanceId));
    }
}
