package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SandboxProvisionerTest {

    @Mock private CfApiClient cfApiClient;
    @Mock private SandboxRegistry registry;
    @Mock private TextEncryptor encryptor;
    @Mock private ResourceChecker resourceChecker;

    private SandboxProvisioner provisioner;

    private static final String MANAGER_ID  = "mgr-1";
    private static final String INSTANCE_ID = "inst-12345678";

    @BeforeEach
    void setUp() {
        provisioner = new SandboxProvisioner(cfApiClient, registry, encryptor, resourceChecker);
    }

    // ── already provisioned (race condition) ──────────────────────────────────

    @Test
    void provision_alreadyInRegistryWhenSemaphoreAcquired_returnsExisting() {
        SandboxConnection existing = new SandboxConnection(
                "db-host", 5432, "db", "user", "pass", "cf-inst", true);
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.of(existing));

        SandboxConnection result = provisioner.provision(MANAGER_ID, INSTANCE_ID, provisionConfig());

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(cfApiClient, resourceChecker);
    }

    // ── insufficient resources ────────────────────────────────────────────────

    @Test
    void provision_insufficientResources_throwsInsufficientResourcesException() {
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.empty());
        when(resourceChecker.check(eq(MANAGER_ID), any()))
                .thenReturn(ResourceCheckResult.insufficient("quota exceeded"));

        assertThatThrownBy(() -> provisioner.provision(MANAGER_ID, INSTANCE_ID, provisionConfig()))
                .isInstanceOf(InsufficientResourcesException.class)
                .hasMessageContaining("quota exceeded");

        verifyNoInteractions(cfApiClient);
    }

    // ── success path ──────────────────────────────────────────────────────────

    @Test
    void provision_success_callsCfApiAndRegisters() {
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.empty());
        when(resourceChecker.check(eq(MANAGER_ID), any()))
                .thenReturn(ResourceCheckResult.sufficient());

        CfApiClient.ServiceKeyCredentials creds = new CfApiClient.ServiceKeyCredentials(
                "cred-host", 5432, "cred-db", "cred-user", "cred-pass");
        when(cfApiClient.createServiceKey(anyString(), anyString(), anyString())).thenReturn(creds);

        SandboxConnection result = provisioner.provision(MANAGER_ID, INSTANCE_ID, provisionConfig());

        verify(cfApiClient).createServiceInstance(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cfApiClient).pollUntilReady(anyString(), anyString(), any());
        verify(cfApiClient).createServiceKey(anyString(), anyString(), anyString());
        verify(registry).register(eq(MANAGER_ID), eq(INSTANCE_ID), any(), eq(encryptor));

        assertThat(result.host()).isEqualTo("cred-host");
        assertThat(result.provisioned()).isTrue();
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private MonitoringConfig.ProvisionConfig provisionConfig() {
        MonitoringConfig.ProvisionConfig cfg = new MonitoringConfig.ProvisionConfig();
        cfg.setOrg("test-org");
        cfg.setSpace("test-space");
        cfg.setService("postgresql");
        cfg.setPlan("small");
        cfg.setInstanceNamePrefix("restore-sandbox");
        return cfg;
    }
}
