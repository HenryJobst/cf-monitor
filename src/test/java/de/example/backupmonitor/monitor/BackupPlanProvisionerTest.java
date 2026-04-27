package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.BackupManagerClient;
import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.S3FileDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackupPlanProvisionerTest {

    @Mock private CfApiClient cfApiClient;
    @Mock private BackupManagerClient managerClient;

    private MonitoringConfig config;
    private BackupPlanProvisioner provisioner;

    private static final String MANAGER_ID  = "mgr-1";
    private static final String INSTANCE_ID = "inst-001";
    private static final String SPACE_GUID  = "space-guid-001";

    @BeforeEach
    void setUp() {
        config = new MonitoringConfig();
        MonitoringConfig.ManagerConfig manager = new MonitoringConfig.ManagerConfig();
        manager.setId(MANAGER_ID);
        MonitoringConfig.CfConfig cf = new MonitoringConfig.CfConfig();
        cf.setSpaceGuid(SPACE_GUID);
        manager.setCf(cf);

        MonitoringConfig.ServiceInstanceConfig instance = new MonitoringConfig.ServiceInstanceConfig();
        instance.setId(INSTANCE_ID);
        manager.setInstances(List.of(instance));

        config.setManagers(List.of(manager));

        provisioner = new BackupPlanProvisioner(config, cfApiClient, managerClient);
    }

    // ── disabled / misconfigured ───────────────────────────────────────────────

    @Test
    void tryProvision_disabled_returnsEmptyWithoutApiCalls() {
        config.getAutoProvision().setEnabled(false);

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(cfApiClient, managerClient);
    }

    @Test
    void tryProvision_noSpaceGuid_returnsEmptyWithoutApiCalls() {
        config.getAutoProvision().setEnabled(true);
        config.getManagers().get(0).getCf().setSpaceGuid("");

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(cfApiClient);
    }

    @Test
    void tryProvision_nullSpaceGuid_returnsEmpty() {
        config.getAutoProvision().setEnabled(true);
        config.getManagers().get(0).getCf().setSpaceGuid(null);

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(cfApiClient);
    }

    // ── no S3 service found ────────────────────────────────────────────────────

    @Test
    void tryProvision_noS3ServiceInSpace_returnsEmpty() {
        config.getAutoProvision().setEnabled(true);
        when(cfApiClient.findServiceInstancesByOffering(MANAGER_ID, SPACE_GUID, "s3"))
                .thenReturn(List.of());

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(managerClient);
    }

    @Test
    void tryProvision_customS3Label_passedToApiClient() {
        config.getAutoProvision().setEnabled(true);
        config.getAutoProvision().setS3ServiceLabel("minio");
        when(cfApiClient.findServiceInstancesByOffering(MANAGER_ID, SPACE_GUID, "minio"))
                .thenReturn(List.of());

        provisioner.tryProvision(MANAGER_ID, INSTANCE_ID);

        verify(cfApiClient).findServiceInstancesByOffering(MANAGER_ID, SPACE_GUID, "minio");
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void tryProvision_s3Found_createsAndReturnsPlan() {
        config.getAutoProvision().setEnabled(true);
        CfApiClient.S3ServiceCandidate s3 = new CfApiClient.S3ServiceCandidate("s3-guid", "my-s3");
        S3FileDestination dest = buildDestination();
        BackupPlan plan = new BackupPlan();

        when(cfApiClient.findServiceInstancesByOffering(MANAGER_ID, SPACE_GUID, "s3"))
                .thenReturn(List.of(s3));
        when(cfApiClient.getS3Credentials(MANAGER_ID, "s3-guid", "my-s3")).thenReturn(dest);
        when(managerClient.createBackupPlan(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(Optional.of(plan));

        Optional<BackupPlan> result = provisioner.tryProvision(MANAGER_ID, INSTANCE_ID);

        assertThat(result).isPresent().contains(plan);
    }

    @Test
    void tryProvision_defaultSchedule_isDaily() {
        config.getAutoProvision().setEnabled(true);
        CfApiClient.S3ServiceCandidate s3 = new CfApiClient.S3ServiceCandidate("s3-guid", "my-s3");

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(s3));
        when(cfApiClient.getS3Credentials(any(), any(), any())).thenReturn(buildDestination());
        when(managerClient.createBackupPlan(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        provisioner.tryProvision(MANAGER_ID, INSTANCE_ID);

        verify(managerClient).createBackupPlan(any(), any(), eq("0 2 * * *"), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void tryProvision_customSchedule_passedToManager() {
        config.getAutoProvision().setEnabled(true);
        config.getAutoProvision().setBackupSchedule("0 4 * * 0");
        CfApiClient.S3ServiceCandidate s3 = new CfApiClient.S3ServiceCandidate("s3-guid", "my-s3");

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(s3));
        when(cfApiClient.getS3Credentials(any(), any(), any())).thenReturn(buildDestination());
        when(managerClient.createBackupPlan(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        provisioner.tryProvision(MANAGER_ID, INSTANCE_ID);

        verify(managerClient).createBackupPlan(any(), any(), eq("0 4 * * 0"), any(), anyInt(), any(), any(), any(), any());
    }

    // ── multiple candidates ────────────────────────────────────────────────────

    @Test
    void tryProvision_multipleS3Instances_usesFirst() {
        config.getAutoProvision().setEnabled(true);
        var s3a = new CfApiClient.S3ServiceCandidate("guid-a", "s3-a");
        var s3b = new CfApiClient.S3ServiceCandidate("guid-b", "s3-b");

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(s3a, s3b));
        when(cfApiClient.getS3Credentials(MANAGER_ID, "guid-a", "s3-a"))
                .thenReturn(buildDestination());
        when(managerClient.createBackupPlan(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        provisioner.tryProvision(MANAGER_ID, INSTANCE_ID);

        verify(cfApiClient).getS3Credentials(MANAGER_ID, "guid-a", "s3-a");
        verify(cfApiClient, never()).getS3Credentials(MANAGER_ID, "guid-b", "s3-b");
    }

    // ── validation failures ────────────────────────────────────────────────────

    @Test
    void tryProvision_credentialsMissingAuthKey_returnsEmpty() {
        config.getAutoProvision().setEnabled(true);
        S3FileDestination incomplete = new S3FileDestination();
        incomplete.setBucket("my-bucket");
        // authKey/authSecret null

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(new CfApiClient.S3ServiceCandidate("g", "n")));
        when(cfApiClient.getS3Credentials(any(), any(), any())).thenReturn(incomplete);

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(managerClient);
    }

    @Test
    void tryProvision_credentialsMissingBucket_returnsEmpty() {
        config.getAutoProvision().setEnabled(true);
        S3FileDestination noBucket = new S3FileDestination();
        noBucket.setAuthKey("key");
        noBucket.setAuthSecret("secret");
        // bucket null

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(new CfApiClient.S3ServiceCandidate("g", "n")));
        when(cfApiClient.getS3Credentials(any(), any(), any())).thenReturn(noBucket);

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(managerClient);
    }

    @Test
    void tryProvision_credentialsFetchThrows_returnsEmpty() {
        config.getAutoProvision().setEnabled(true);

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(new CfApiClient.S3ServiceCandidate("g", "n")));
        when(cfApiClient.getS3Credentials(any(), any(), any()))
                .thenThrow(new RuntimeException("CF API unavailable"));

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
        verifyNoInteractions(managerClient);
    }

    @Test
    void tryProvision_planCreationReturnsEmpty_propagatesEmpty() {
        config.getAutoProvision().setEnabled(true);

        when(cfApiClient.findServiceInstancesByOffering(any(), any(), any()))
                .thenReturn(List.of(new CfApiClient.S3ServiceCandidate("g", "n")));
        when(cfApiClient.getS3Credentials(any(), any(), any())).thenReturn(buildDestination());
        when(managerClient.createBackupPlan(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(provisioner.tryProvision(MANAGER_ID, INSTANCE_ID)).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private S3FileDestination buildDestination() {
        S3FileDestination dest = new S3FileDestination();
        dest.setAuthKey("AKIAIOSFODNN7EXAMPLE");
        dest.setAuthSecret("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        dest.setBucket("my-backups");
        dest.setEndpoint("https://s3.example.com");
        dest.setRegion("eu-central-1");
        return dest;
    }
}
