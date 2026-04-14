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

    // ── triggerRestore ─────────────────────────────────────────────────────────

    @Test
    void triggerRestore_sendsCorrectRequestAndReturnsJobId() {
        stubFor(put(urlEqualTo("/restore"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        String returned = client.triggerRestore(
                MANAGER_ID, JOB_ID,
                buildDestination(), "backup.tar.gz",
                buildSandbox());

        assertThat(returned).isEqualTo(JOB_ID);
        verify(putRequestedFor(urlEqualTo("/restore"))
                .withHeader("Authorization", containing("Basic "))
                .withRequestBody(matchingJsonPath("$.id", equalTo(JOB_ID)))
                .withRequestBody(matchingJsonPath("$.destination.type", equalTo("S3")))
                .withRequestBody(matchingJsonPath("$.destination.bucket", equalTo("my-bucket")))
                .withRequestBody(matchingJsonPath("$.destination.filename", equalTo("backup.tar.gz")))
                .withRequestBody(matchingJsonPath("$.restore.host", equalTo("sandbox-host")))
                .withRequestBody(matchingJsonPath("$.restore.database", equalTo("restore_db"))));
    }

    @Test
    void triggerRestore_mapsS3EndpointToAuthUrl() {
        stubFor(put(urlEqualTo("/restore"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        client.triggerRestore(MANAGER_ID, JOB_ID, buildDestination(), "backup.tar.gz", buildSandbox());

        verify(putRequestedFor(urlEqualTo("/restore"))
                .withRequestBody(matchingJsonPath("$.destination.authUrl",
                        equalTo("https://s3.example.com"))));
    }

    // ── pollStatus ─────────────────────────────────────────────────────────────

    @Test
    void pollStatus_running_returnsRunning() {
        stubFor(get(urlEqualTo("/restore/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"running","state":"restore"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.RUNNING);
    }

    @Test
    void pollStatus_success_returnsSucceeded() {
        stubFor(get(urlEqualTo("/restore/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"success","state":"finished"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.SUCCEEDED);
    }

    @Test
    void pollStatus_failed_returnsFailed() {
        stubFor(get(urlEqualTo("/restore/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"failed","state":"restore"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.FAILED);
    }

    @Test
    void pollStatus_serverError_returnsUnknown() {
        stubFor(get(urlEqualTo("/restore/" + JOB_ID))
                .willReturn(serverError()));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.UNKNOWN);
    }

    @Test
    void pollStatus_unknownStatusString_returnsUnknown() {
        stubFor(get(urlEqualTo("/restore/" + JOB_ID))
                .willReturn(okJson("""
                        {"status":"something_unexpected"}
                        """)));

        assertThat(client.pollStatus(MANAGER_ID, JOB_ID))
                .isEqualTo(AgentClient.AgentRestoreStatus.UNKNOWN);
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

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
