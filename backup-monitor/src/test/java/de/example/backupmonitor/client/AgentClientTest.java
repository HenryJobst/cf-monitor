package de.example.backupmonitor.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.S3FileDestination;
import de.example.backupmonitor.sandbox.SandboxConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class AgentClientTest {

    private static final String MANAGER_ID = "mgr-1";
    private static final String JOB_ID = "agent-job-001";

    private AgentClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ManagerConfig manager = new MonitoringConfig.ManagerConfig();
        manager.setId(MANAGER_ID);
        manager.setAgentUrl("http://localhost:" + wm.getHttpPort());
        manager.setAgentUsername("admin");
        manager.setAgentPassword("secret");
        config.setManagers(List.of(manager));

        client = new AgentClient(config);
    }

    @Test
    void triggerRestore_success_returnsJobId() {
        stubFor(post(urlEqualTo("/v2/restore_jobs"))
                .willReturn(aResponse().withStatus(201)));

        String returned = client.triggerRestore(
                MANAGER_ID, JOB_ID,
                buildDestination(), "backup.tar.gz",
                buildSandbox());

        assertThat(returned).isEqualTo(JOB_ID);
        verify(postRequestedFor(urlEqualTo("/v2/restore_jobs"))
                .withHeader("Authorization", containing("Basic ")));
    }

    @Test
    void pollStatus_running_returnsRunning() {
        stubFor(get(urlEqualTo("/v2/restore_jobs/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"RUNNING"}
                        """)));

        AgentClient.AgentRestoreStatus status = client.pollStatus(MANAGER_ID, JOB_ID);

        assertThat(status).isEqualTo(AgentClient.AgentRestoreStatus.RUNNING);
    }

    @Test
    void pollStatus_succeeded_returnsSucceeded() {
        stubFor(get(urlEqualTo("/v2/restore_jobs/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"SUCCEEDED"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.SUCCEEDED);
    }

    @Test
    void pollStatus_failed_returnsFailed() {
        stubFor(get(urlEqualTo("/v2/restore_jobs/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"FAILED"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.FAILED);
    }

    @Test
    void pollStatus_serverError_returnsUnknown() {
        stubFor(get(urlEqualTo("/v2/restore_jobs/" + JOB_ID))
                .willReturn(serverError()));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.UNKNOWN);
    }

    @Test
    void pollStatus_unknownStatusString_returnsUnknown() {
        stubFor(get(urlEqualTo("/v2/restore_jobs/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"SOMETHING_UNEXPECTED"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.UNKNOWN);
    }

    private S3FileDestination buildDestination() {
        S3FileDestination dest = new S3FileDestination();
        dest.setBucket("my-bucket");
        dest.setEndpoint("https://s3.example.com");
        dest.setAuthKey("key");
        dest.setAuthSecret("secret");
        return dest;
    }

    private SandboxConnection buildSandbox() {
        return new SandboxConnection(
                "sandbox-host", 5432, "restore_db",
                "restore_user", "restore_pass",
                null, false);
    }
}
