package de.example.backupmonitor.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@WireMockTest
@ExtendWith(MockitoExtension.class)
class BackupManagerClientTest {

    private static final String MANAGER_ID = "mgr-1";
    private static final String INSTANCE_ID = "inst-001";

    @Mock
    private CfTokenServiceRegistry tokenRegistry;

    private BackupManagerClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        when(tokenRegistry.getToken(MANAGER_ID)).thenReturn("test-bearer-token");

        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ManagerConfig manager = new MonitoringConfig.ManagerConfig();
        manager.setId(MANAGER_ID);
        manager.setUrl("http://localhost:" + wm.getHttpPort());
        config.setManagers(List.of(manager));

        client = new BackupManagerClient(config, tokenRegistry);
    }

    @Test
    void getBackupPlan_planExists_returnsPlan() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_plans"))
                .willReturn(okJson("""
                        [{"id":"plan-1","instance_id":"%s","active":true,"paused":false}]
                        """.formatted(INSTANCE_ID))));

        Optional<BackupPlan> result = client.getBackupPlan(MANAGER_ID, INSTANCE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getIdAsString()).isEqualTo("plan-1");
        assertThat(result.get().isActive()).isTrue();
        assertThat(result.get().isPaused()).isFalse();
    }

    @Test
    void getBackupPlan_emptyList_returnsEmpty() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_plans"))
                .willReturn(okJson("[]")));

        assertThat(client.getBackupPlan(MANAGER_ID, INSTANCE_ID)).isEmpty();
    }

    @Test
    void getBackupPlan_serverError_returnsEmpty() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_plans"))
                .willReturn(serverError()));

        assertThat(client.getBackupPlan(MANAGER_ID, INSTANCE_ID)).isEmpty();
    }

    @Test
    void getLatestJob_jobFound_returnsJob() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_jobs?limit=1"))
                .willReturn(okJson("""
                        [{"id":"job-42","instance_id":"%s","status":"SUCCEEDED"}]
                        """.formatted(INSTANCE_ID))));

        Optional<BackupJob> result = client.getLatestJob(MANAGER_ID, INSTANCE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getIdAsString()).isEqualTo("job-42");
        assertThat(result.get().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void getLatestJob_emptyList_returnsEmpty() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_jobs?limit=1"))
                .willReturn(okJson("[]")));

        assertThat(client.getLatestJob(MANAGER_ID, INSTANCE_ID)).isEmpty();
    }

    @Test
    void getLatestBackupJob_succeededJobFound_returnsJob() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID
                + "/backup_jobs?status=SUCCEEDED&limit=1"))
                .willReturn(okJson("""
                        [{"id":"job-99","instance_id":"%s","status":"SUCCEEDED"}]
                        """.formatted(INSTANCE_ID))));

        Optional<BackupJob> result = client.getLatestBackupJob(MANAGER_ID, INSTANCE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void getJobById_found_returnsJob() {
        stubFor(get(urlEqualTo("/v2/backup_jobs/job-77"))
                .willReturn(okJson("""
                        {"id":"job-77","instance_id":"%s","status":"FAILED"}
                        """.formatted(INSTANCE_ID))));

        Optional<BackupJob> result = client.getJobById(MANAGER_ID, "job-77");

        assertThat(result).isPresent();
        assertThat(result.get().getIdAsString()).isEqualTo("job-77");
        assertThat(result.get().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void getJobById_notFound_returnsEmpty() {
        stubFor(get(urlEqualTo("/v2/backup_jobs/missing-job"))
                .willReturn(notFound()));

        assertThat(client.getJobById(MANAGER_ID, "missing-job")).isEmpty();
    }

    @Test
    void bearerToken_isSentWithEveryRequest() {
        stubFor(get(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_plans"))
                .willReturn(okJson("[]")));

        client.getBackupPlan(MANAGER_ID, INSTANCE_ID);

        verify(getRequestedFor(urlEqualTo("/v2/service_instances/" + INSTANCE_ID + "/backup_plans"))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token")));
    }
}
