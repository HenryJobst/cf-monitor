package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.config.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SandboxManagerTest {

    @Mock private SandboxRegistry registry;
    @Mock private SandboxProvisioner provisioner;

    private static final String INSTANCE_ID = "inst-1";
    private static final String MANAGER_ID  = "mgr-1";

    // ── existing in registry ──────────────────────────────────────────────────

    @Test
    void getSandbox_existsInRegistry_returnsRegistryConnection() {
        SandboxConnection existing = new SandboxConnection(
                "db-host", 5432, "db", "user", "pass", null, false);
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.of(existing));

        SandboxConnection result = buildManager("existing").getSandbox(INSTANCE_ID);

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(provisioner);
    }

    // ── mode=existing from config ─────────────────────────────────────────────

    @Test
    void getSandbox_existingMode_buildsConnectionFromConfig() {
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.empty());
        MonitoringConfig config = buildConfig("existing");

        SandboxManager manager = new SandboxManager(registry, provisioner, config);
        SandboxConnection result = manager.getSandbox(INSTANCE_ID);

        assertThat(result.host()).isEqualTo("sandbox-host");
        assertThat(result.port()).isEqualTo(5432);
        assertThat(result.database()).isEqualTo("sandbox-db");
        assertThat(result.provisioned()).isFalse();
        verifyNoInteractions(provisioner);
    }

    // ── mode=provision ────────────────────────────────────────────────────────

    @Test
    void getSandbox_provisionMode_callsProvisioner() {
        when(registry.findExisting(INSTANCE_ID)).thenReturn(Optional.empty());

        SandboxConnection provisioned = new SandboxConnection(
                "prov-host", 5432, "prov-db", "prov-user", "prov-pass", "cf-inst", true);
        when(provisioner.provision(eq(MANAGER_ID), eq(INSTANCE_ID), any()))
                .thenReturn(provisioned);

        MonitoringConfig config = buildConfig("provision");
        SandboxManager manager = new SandboxManager(registry, provisioner, config);
        SandboxConnection result = manager.getSandbox(INSTANCE_ID);

        assertThat(result).isSameAs(provisioned);
        verify(provisioner).provision(eq(MANAGER_ID), eq(INSTANCE_ID), any());
    }

    // ── no sandbox config ─────────────────────────────────────────────────────

    @Test
    void getSandbox_noConfigForInstance_throwsIllegalState() {
        when(registry.findExisting("unknown-inst")).thenReturn(Optional.empty());

        MonitoringConfig config = buildConfig("existing");
        SandboxManager manager = new SandboxManager(registry, provisioner, config);

        assertThatThrownBy(() -> manager.getSandbox("unknown-inst"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No sandbox config");
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private SandboxManager buildManager(String mode) {
        return new SandboxManager(registry, provisioner, buildConfig(mode));
    }

    private MonitoringConfig buildConfig(String mode) {
        MonitoringConfig config = new MonitoringConfig();

        MonitoringConfig.ManagerConfig mgr = new MonitoringConfig.ManagerConfig();
        mgr.setId(MANAGER_ID);
        MonitoringConfig.ServiceInstanceConfig inst = new MonitoringConfig.ServiceInstanceConfig();
        inst.setId(INSTANCE_ID);
        mgr.setInstances(List.of(inst));
        config.setManagers(List.of(mgr));

        MonitoringConfig.SandboxConfig sandbox = new MonitoringConfig.SandboxConfig();
        sandbox.setInstanceId(INSTANCE_ID);
        sandbox.setMode(mode);

        MonitoringConfig.ExistingSandboxConfig existing = new MonitoringConfig.ExistingSandboxConfig();
        existing.setHost("sandbox-host");
        existing.setPort(5432);
        existing.setDatabase("sandbox-db");
        existing.setUsername("sandbox-user");
        existing.setPassword("sandbox-pass");
        sandbox.setExisting(existing);

        MonitoringConfig.ProvisionConfig provision = new MonitoringConfig.ProvisionConfig();
        provision.setOrg("test-org");
        provision.setSpace("test-space");
        sandbox.setProvision(provision);

        config.getRestoreTest().setSandboxes(List.of(sandbox));
        return config;
    }
}
