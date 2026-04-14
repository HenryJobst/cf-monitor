package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@Component
@Slf4j
public class SandboxProvisioner {

    private final CfApiClient cfApiClient;
    private final SandboxRegistry registry;
    private final TextEncryptor encryptor;
    private final ResourceChecker resourceChecker;

    private final Semaphore provisioningSemaphore = new Semaphore(1, true);

    public SandboxProvisioner(CfApiClient cfApiClient, SandboxRegistry registry,
                               TextEncryptor encryptor, ResourceChecker resourceChecker) {
        this.cfApiClient = cfApiClient;
        this.registry = registry;
        this.encryptor = encryptor;
        this.resourceChecker = resourceChecker;
    }

    public SandboxConnection provision(String managerId, String instanceId,
                                        MonitoringConfig.ProvisionConfig cfg) {
        boolean acquired = false;
        try {
            log.debug("Waiting for provisioning semaphore for instance {}", instanceId);
            provisioningSemaphore.acquire();
            acquired = true;
            log.info("Provisioning sandbox for instance {}", instanceId);

            Optional<SandboxConnection> existing = registry.findExisting(instanceId);
            if (existing.isPresent()) {
                log.debug("Sandbox already provisioned by another thread");
                return existing.get();
            }

            ResourceCheckResult resources = resourceChecker.check(managerId, cfg);
            if (!resources.hasSufficientQuota()) {
                throw new InsufficientResourcesException(resources.reason());
            }

            String instanceName = cfg.getInstanceNamePrefix()
                    + "-" + instanceId.substring(0, Math.min(8, instanceId.length()));

            cfApiClient.createServiceInstance(managerId, instanceName,
                    cfg.getService(), cfg.getPlan(), cfg.getSpace());
            cfApiClient.pollUntilReady(managerId, instanceName, Duration.ofMinutes(10));

            CfApiClient.ServiceKeyCredentials creds = cfApiClient.createServiceKey(
                    managerId, instanceName, instanceName + "-key");

            SandboxConnection conn = new SandboxConnection(
                    creds.host(), creds.port(), creds.database(),
                    creds.username(), creds.password(),
                    instanceName, true);
            registry.register(managerId, instanceId, conn, encryptor);
            return conn;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Provisioning interrupted", e);
        } finally {
            if (acquired) provisioningSemaphore.release();
        }
    }

    public void reset(SandboxConnection sandbox) {
        String jdbcUrl = "jdbc:postgresql://" + sandbox.host() + ":"
                + sandbox.port() + "/" + sandbox.database();
        try (Connection conn = DriverManager.getConnection(jdbcUrl,
                sandbox.username(), sandbox.password());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
            log.debug("Sandbox schema reset for host {}", sandbox.host());
        } catch (Exception e) {
            log.warn("Failed to reset sandbox schema: {}", e.getMessage());
        }
    }
}
