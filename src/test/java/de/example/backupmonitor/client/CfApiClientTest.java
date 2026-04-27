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

    // ── findServiceInstancesByOffering ────────────────────────────────────────

    @Test
    void findServiceInstancesByOffering_matchingOffering_returnsCandidate() {
        stubFor(get(urlPathEqualTo("/v3/service_plans"))
                .withQueryParam("service_offering_names", equalTo("s3"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "plan-guid"}]}
                        """)));
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .withQueryParam("space_guids", equalTo("space-guid"))
                .withQueryParam("service_plan_guids", equalTo("plan-guid"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "inst-s3-guid", "name": "my-s3"}]}
                        """)));

        List<CfApiClient.S3ServiceCandidate> result =
                client.findServiceInstancesByOffering(MANAGER_ID, "space-guid", "s3");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).guid()).isEqualTo("inst-s3-guid");
        assertThat(result.get(0).name()).isEqualTo("my-s3");
    }

    @Test
    void findServiceInstancesByOffering_differentOffering_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .willReturn(okJson("""
                        {
                          "resources": [{
                            "guid": "inst-pg-guid",
                            "name": "my-postgres",
                            "relationships": {
                              "service_plan": {"data": {"guid": "plan-guid"}}
                            }
                          }],
                          "included": {
                            "service_plans": [{
                              "guid": "plan-guid",
                              "relationships": {
                                "service_offering": {"data": {"guid": "offering-guid"}}
                              }
                            }],
                            "service_offerings": [{"guid": "offering-guid", "name": "postgresql"}]
                          }
                        }
                        """)));

        List<CfApiClient.S3ServiceCandidate> result =
                client.findServiceInstancesByOffering(MANAGER_ID, "space-guid", "s3");

        assertThat(result).isEmpty();
    }

    @Test
    void findServiceInstancesByOffering_multipleInstances_returnsMatchingOnly() {
        stubFor(get(urlPathEqualTo("/v3/service_plans"))
                .withQueryParam("service_offering_names", equalTo("s3"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "plan-s3"}]}
                        """)));
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .withQueryParam("space_guids", equalTo("space-guid"))
                .withQueryParam("service_plan_guids", equalTo("plan-s3"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "inst-s3", "name": "s3-service"}]}
                        """)));

        List<CfApiClient.S3ServiceCandidate> result =
                client.findServiceInstancesByOffering(MANAGER_ID, "space-guid", "s3");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).guid()).isEqualTo("inst-s3");
    }

    @Test
    void findServiceInstancesByOffering_apiError_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .willReturn(serverError()));

        List<CfApiClient.S3ServiceCandidate> result =
                client.findServiceInstancesByOffering(MANAGER_ID, "space-guid", "s3");

        assertThat(result).isEmpty();
    }

    @Test
    void findServiceInstancesByOffering_emptyResources_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/v3/service_instances"))
                .willReturn(okJson("""
                        {"resources": [], "included": {}}
                        """)));

        assertThat(client.findServiceInstancesByOffering(MANAGER_ID, "space-guid", "s3")).isEmpty();
    }

    // ── getS3Credentials ──────────────────────────────────────────────────────

    @Test
    void getS3Credentials_existingKey_reusesItWithoutCreating() {
        // Liste vorhandener Keys → ein Key gefunden
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings"))
                .withQueryParam("service_instance_guids", equalTo("inst-guid"))
                .withQueryParam("type", equalTo("key"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "key-guid-existing"}]}
                        """)));

        // Details des Keys
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings/key-guid-existing/details"))
                .willReturn(okJson("""
                        {"credentials": {
                          "access_key_id": "AKID",
                          "secret_access_key": "SECRET",
                          "bucket": "my-bucket",
                          "endpoint": "https://s3.example.com",
                          "region": "eu-central-1"
                        }}
                        """)));

        var dest = client.getS3Credentials(MANAGER_ID, "inst-guid", "my-s3");

        assertThat(dest.getAuthKey()).isEqualTo("AKID");
        assertThat(dest.getAuthSecret()).isEqualTo("SECRET");
        assertThat(dest.getBucket()).isEqualTo("my-bucket");
        assertThat(dest.getEndpoint()).isEqualTo("https://s3.example.com");
        assertThat(dest.getRegion()).isEqualTo("eu-central-1");

        // kein POST – vorhandener Key wurde wiederverwendet
        verify(0, postRequestedFor(urlPathEqualTo("/v3/service_credential_bindings")));
    }

    @Test
    void getS3Credentials_noExistingKey_createsNewKey() {
        // keine vorhandenen Keys
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings"))
                .withQueryParam("service_instance_guids", equalTo("inst-guid"))
                .withQueryParam("type", equalTo("key"))
                .willReturn(okJson("""
                        {"resources": []}
                        """)));

        // POST neuer Key
        stubFor(post(urlPathEqualTo("/v3/service_credential_bindings"))
                .willReturn(aResponse().withStatus(201)));

        // Auflösen des neuen Keys per Name
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings"))
                .withQueryParam("names", equalTo("cf-backup-monitor-my-s3-key"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "key-guid-new"}]}
                        """)));

        // Details des neuen Keys
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings/key-guid-new/details"))
                .willReturn(okJson("""
                        {"credentials": {
                          "access_key_id": "NEW-AKID",
                          "secret_access_key": "NEW-SECRET",
                          "bucket": "new-bucket"
                        }}
                        """)));

        var dest = client.getS3Credentials(MANAGER_ID, "inst-guid", "my-s3");

        assertThat(dest.getAuthKey()).isEqualTo("NEW-AKID");
        assertThat(dest.getAuthSecret()).isEqualTo("NEW-SECRET");
        assertThat(dest.getBucket()).isEqualTo("new-bucket");

        verify(postRequestedFor(urlPathEqualTo("/v3/service_credential_bindings"))
                .withRequestBody(matchingJsonPath("$.name",
                        equalTo("cf-backup-monitor-my-s3-key")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("key"))));
    }

    @Test
    void getS3Credentials_minioStyleFields_mappedCorrectly() {
        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings"))
                .withQueryParam("type", equalTo("key"))
                .willReturn(okJson("""
                        {"resources": [{"guid": "key-minio"}]}
                        """)));

        stubFor(get(urlPathEqualTo("/v3/service_credential_bindings/key-minio/details"))
                .willReturn(okJson("""
                        {"credentials": {
                          "access_key":  "MINIO-KEY",
                          "secret_key":  "MINIO-SECRET",
                          "bucket":      "minio-bucket",
                          "host":        "http://minio.internal:9000"
                        }}
                        """)));

        var dest = client.getS3Credentials(MANAGER_ID, "inst-guid", "minio-svc");

        assertThat(dest.getAuthKey()).isEqualTo("MINIO-KEY");
        assertThat(dest.getAuthSecret()).isEqualTo("MINIO-SECRET");
        assertThat(dest.getEndpoint()).isEqualTo("http://minio.internal:9000");
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
