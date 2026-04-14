package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrphanedSandboxCleanerTest {

    @Mock private SandboxRegistryRepository registryRepo;
    @Mock private CfApiClient cfApiClient;

    private static final String CONFIGURED_INSTANCE = "inst-1";
    private static final String ORPHANED_INSTANCE   = "inst-orphan";
    private static final String MANAGER_ID          = "mgr-1";

    // ── no orphans ────────────────────────────────────────────────────────────

    @Test
    void cleanupOrphans_allInstancesInConfig_noDeletion() {
        SandboxRegistryEntry entry = provisionEntry(CONFIGURED_INSTANCE, "guid-1");
        when(registryRepo.findAll()).thenReturn(List.of(entry));

        buildCleaner().cleanupOrphans();

        verifyNoInteractions(cfApiClient);
        verify(registryRepo, never()).delete(any());
    }

    // ── orphaned PROVISION entry ──────────────────────────────────────────────

    @Test
    void cleanupOrphans_orphanedProvisionEntry_deletesFromCfAndRegistry() {
        SandboxRegistryEntry orphan = provisionEntry(ORPHANED_INSTANCE, "guid-orphan");
        when(registryRepo.findAll()).thenReturn(List.of(orphan));

        buildCleaner().cleanupOrphans();

        verify(cfApiClient).deleteServiceInstance(MANAGER_ID, "guid-orphan");
        verify(registryRepo).delete(orphan);
    }

    // ── EXISTING mode entry not in config ─────────────────────────────────────

    @Test
    void cleanupOrphans_existingModeOrphan_isNotDeprovisioned() {
        SandboxRegistryEntry existing = existingEntry(ORPHANED_INSTANCE);
        when(registryRepo.findAll()).thenReturn(List.of(existing));

        buildCleaner().cleanupOrphans();

        verifyNoInteractions(cfApiClient);
        verify(registryRepo, never()).delete(any());
    }

    // ── CF delete throws → registry entry kept ────────────────────────────────

    @Test
    void cleanupOrphans_cfDeleteFails_registryEntryNotDeleted() {
        SandboxRegistryEntry orphan = provisionEntry(ORPHANED_INSTANCE, "guid-orphan");
        when(registryRepo.findAll()).thenReturn(List.of(orphan));
        doThrow(new RuntimeException("CF error"))
                .when(cfApiClient).deleteServiceInstance(any(), any());

        buildCleaner().cleanupOrphans(); // should not throw

        verify(registryRepo, never()).delete(any());
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private OrphanedSandboxCleaner buildCleaner() {
        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ManagerConfig mgr = new MonitoringConfig.ManagerConfig();
        mgr.setId(MANAGER_ID);
        MonitoringConfig.ServiceInstanceConfig inst = new MonitoringConfig.ServiceInstanceConfig();
        inst.setId(CONFIGURED_INSTANCE);
        mgr.setInstances(List.of(inst));
        config.setManagers(List.of(mgr));
        return new OrphanedSandboxCleaner(registryRepo, cfApiClient, config);
    }

    private SandboxRegistryEntry provisionEntry(String instanceId, String cfGuid) {
        SandboxRegistryEntry e = new SandboxRegistryEntry();
        e.setInstanceId(instanceId);
        e.setManagerId(MANAGER_ID);
        e.setMode(SandboxMode.PROVISION);
        e.setCfServiceInstanceGuid(cfGuid);
        e.setCfServiceInstanceName("cf-" + instanceId);
        e.setSandboxHost("host");
        e.setSandboxPort(5432);
        e.setSandboxDatabase("db");
        return e;
    }

    private SandboxRegistryEntry existingEntry(String instanceId) {
        SandboxRegistryEntry e = new SandboxRegistryEntry();
        e.setInstanceId(instanceId);
        e.setManagerId(MANAGER_ID);
        e.setMode(SandboxMode.EXISTING);
        e.setSandboxHost("host");
        e.setSandboxPort(5432);
        e.setSandboxDatabase("db");
        return e;
    }
}
