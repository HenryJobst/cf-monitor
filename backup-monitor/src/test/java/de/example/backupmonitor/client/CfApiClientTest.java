package de.example.backupmonitor.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@WireMockTest
@ExtendWith(MockitoExtension.class)
class CfApiClientTest {

    @Mock
    private CfTokenServiceRegistry tokenRegistry;

    private CfApiClient client;

    private static final String MANAGER_ID = "mgr-1";
    private static final String TOKEN      = "test-bearer-token";

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        when(tokenRegistry.getToken(MANAGER_ID)).thenReturn(TOKEN);

        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ManagerConfig mgr = new MonitoringConfig.ManagerConfig();
        mgr.setId(MANAGER_ID);
        MonitoringConfig.CfConfig cf = new MonitoringConfig.CfConfig();
        cf.setCfApiEndpoint("http://localhost:" + wm.getHttpPort());
        mgr.setCf(cf);
        config.setManagers(List.of(mgr));

        client = new CfApiClient(config, tokenRegistry);
    }

    // ── deleteServiceInstance ─────────────────────────────────────────────────

    @Test
    void deleteServiceInstance_sends204Delete() {
        stubFor(delete(urlPathEqualTo("/v3/service_instances/guid-123"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(aResponse().withStatus(204)));

        client.deleteServiceInstance(MANAGER_ID, "guid-123");

        verify(deleteRequestedFor(urlPathEqualTo("/v3/service_instances/guid-123")));
    }

    // ── createServiceInstance ─────────────────────────────────────────────────

    @Test
    void createServiceInstance_resolvesServicePlanAndPostsInstance() {
        stubFor(get(urlPathEqualTo("/v3/service_plans"))
                .withQueryParam("names", equalTo("small"))
                .withQueryParam("service_offering_names", equalTo("postgresql"))
                .willReturn(okJson("""
                        {"resources":[{"guid":"plan-guid-001"}]}
                        """)));

        stubFor(post(urlPathEqualTo("/v3/service_instances"))
                .willReturn(aResponse().withStatus(201)));

        client.createServiceInstance(MANAGER_ID, "my-instance", "postgresql", "small", "space-guid");

        verify(postRequestedFor(urlPathEqualTo("/v3/service_instances"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("my-instance")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("managed"))));
    }

    @Test
    void createServiceInstance_noPlanFound_throwsException() {
        stubFor(get(urlPathEqualTo("/v3/service_plans"))
                .willReturn(okJson("""
                        {"resources":[]}
                        """)));

        assertThatThrownBy(() ->
                client.createServiceInstance(MANAGER_ID, "my-inst", "postgresql", "large", "space"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No plan found");
    }

    // ── pollUntilReady ────────────────────────────────────────────────────────

    @Test
    void pollUntilReady_instanceReady_returnsImmediately() {
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .withQueryParam("names", equalTo("my-instance"))
                .willReturn(okJson("""
                        {"resources":[{"last_operation":{"state":"succeeded"}}]}
                        """)));

        client.pollUntilReady(MANAGER_ID, "my-instance", Duration.ofSeconds(10));

        verify(getRequestedFor(urlPathEqualTo("/v3/service_instances")));
    }

    @Test
    void pollUntilReady_timeout_throwsException() {
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .withQueryParam("names", equalTo("pending-inst"))
                .willReturn(okJson("""
                        {"resources":[{"last_operation":{"state":"in progress"}}]}
                        """)));

        assertThatThrownBy(() ->
                client.pollUntilReady(MANAGER_ID, "pending-inst", Duration.ofMillis(100)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Timeout");
    }

    // ── createServiceKey ──────────────────────────────────────────────────────

    @Test
    void createServiceKey_resolvesInstanceAndCreatesBinding() {
        // 1. resolveInstanceGuid
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .withQueryParam("names", equalTo("my-instance"))
                .willReturn(okJson("""
                        {"resources":[{"guid":"inst-guid-001"}]}
                        """)));

        // 2. post credential binding
        stubFor(post(urlPathEqualTo("/v3/service_credential_bindings"))
                .willReturn(aResponse().withStatus(201)));

        // 3. get binding details
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings"))
                .withQueryParam("names", equalTo("my-key"))
                .willReturn(okJson("""
                        {"resources":[{"details":{"credentials":{
                            "host":"db-host","port":5432,"dbname":"mydb",
                            "username":"usr","password":"pwd"}}}]}
                        """)));

        CfApiClient.ServiceKeyCredentials creds =
                client.createServiceKey(MANAGER_ID, "my-instance", "my-key");

        assertThat(creds.host()).isEqualTo("db-host");
        assertThat(creds.port()).isEqualTo(5432);
        assertThat(creds.database()).isEqualTo("mydb");
        assertThat(creds.username()).isEqualTo("usr");
        assertThat(creds.password()).isEqualTo("pwd");
    }
}
