package de.example.backupmonitor.client;

import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CfApiClient {

    private final Map<String, String> cfApiEndpoints;
    private final CfTokenServiceRegistry tokenRegistry;
    private final RestClient restClient = RestClient.create();

    public CfApiClient(MonitoringConfig config, CfTokenServiceRegistry tokenRegistry) {
        this.tokenRegistry = tokenRegistry;
        this.cfApiEndpoints = config.getManagers().stream()
                .collect(Collectors.toMap(
                        MonitoringConfig.ManagerConfig::getId,
                        m -> m.getCf().getCfApiEndpoint()));
    }

    public void createServiceInstance(String managerId, String name,
                                       String service, String plan, String spaceGuid) {
        String url = cfApiEndpoint(managerId) + "/v3/service_instances";
        String planGuid = resolvePlanGuid(managerId, service, plan);
        Map<String, Object> body = Map.of(
                "name", name,
                "type", "managed",
                "relationships", Map.of(
                        "space", Map.of("data", Map.of("guid", spaceGuid)),
                        "service_plan", Map.of("data", Map.of("guid", planGuid))));
        post(managerId, url, body);
        log.info("Created CF service instance '{}' (plan: {})", name, plan);
    }

    public void pollUntilReady(String managerId, String instanceName, Duration timeout) {
        String url = cfApiEndpoint(managerId)
                + "/v3/service_instances?names=" + instanceName;
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            try {
                Map<?, ?> response = get(managerId, url, Map.class);
                if (response != null) {
                    var resources = (java.util.List<?>) response.get("resources");
                    if (resources != null && !resources.isEmpty()) {
                        var instance = (Map<?, ?>) resources.get(0);
                        var lastOp = (Map<?, ?>) instance.get("last_operation");
                        if (lastOp != null && "succeeded".equals(lastOp.get("state"))) {
                            log.info("CF service instance '{}' is ready", instanceName);
                            return;
                        }
                    }
                }
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for CF instance", e);
            } catch (Exception e) {
                log.warn("Error polling CF instance status: {}", e.getMessage());
            }
        }
        throw new RuntimeException("Timeout waiting for CF service instance: " + instanceName);
    }

    public ServiceKeyCredentials createServiceKey(String managerId,
                                                    String instanceName, String keyName) {
        String instanceGuid = resolveInstanceGuid(managerId, instanceName);
        String url = cfApiEndpoint(managerId) + "/v3/service_credential_bindings";
        Map<String, Object> body = Map.of(
                "name", keyName,
                "type", "key",
                "relationships", Map.of(
                        "service_instance", Map.of("data", Map.of("guid", instanceGuid))));
        post(managerId, url, body);

        String detailUrl = cfApiEndpoint(managerId)
                + "/v3/service_credential_bindings?names=" + keyName;
        Map<?, ?> response = get(managerId, detailUrl, Map.class);
        return extractCredentials(response);
    }

    public void deleteServiceInstance(String managerId, String instanceGuid) {
        String url = cfApiEndpoint(managerId) + "/v3/service_instances/" + instanceGuid;
        restClient.delete()
                .uri(url)
                .headers(h -> h.setBearerAuth(tokenRegistry.getToken(managerId)))
                .retrieve()
                .toBodilessEntity();
        log.info("Deleted CF service instance {}", instanceGuid);
    }

    private String resolvePlanGuid(String managerId, String service, String plan) {
        String url = cfApiEndpoint(managerId)
                + "/v3/service_plans?names=" + plan + "&service_offering_names=" + service;
        Map<?, ?> response = get(managerId, url, Map.class);
        if (response == null) throw new RuntimeException("No plan found: " + plan);
        var resources = (java.util.List<?>) response.get("resources");
        if (resources == null || resources.isEmpty())
            throw new RuntimeException("No plan found: " + plan);
        return (String) ((Map<?, ?>) resources.get(0)).get("guid");
    }

    private String resolveInstanceGuid(String managerId, String instanceName) {
        String url = cfApiEndpoint(managerId)
                + "/v3/service_instances?names=" + instanceName;
        Map<?, ?> response = get(managerId, url, Map.class);
        if (response == null) throw new RuntimeException("Instance not found: " + instanceName);
        var resources = (java.util.List<?>) response.get("resources");
        if (resources == null || resources.isEmpty())
            throw new RuntimeException("Instance not found: " + instanceName);
        return (String) ((Map<?, ?>) resources.get(0)).get("guid");
    }

    @SuppressWarnings("unchecked")
    private ServiceKeyCredentials extractCredentials(Map<?, ?> response) {
        if (response == null) throw new RuntimeException("No credentials in response");
        var resources = (java.util.List<?>) response.get("resources");
        if (resources == null || resources.isEmpty())
            throw new RuntimeException("No credential binding found");
        var binding = (Map<?, ?>) resources.get(0);
        var details = (Map<?, ?>) binding.get("details");
        var credentials = (Map<String, Object>) details.get("credentials");
        return new ServiceKeyCredentials(
                (String) credentials.get("host"),
                ((Number) credentials.get("port")).intValue(),
                (String) credentials.get("dbname"),
                (String) credentials.get("username"),
                (String) credentials.get("password"));
    }

    private <T> T get(String managerId, String url, Class<T> type) {
        return restClient.get()
                .uri(url)
                .headers(h -> h.setBearerAuth(tokenRegistry.getToken(managerId)))
                .retrieve()
                .body(type);
    }

    private void post(String managerId, String url, Object body) {
        restClient.post()
                .uri(url)
                .headers(h -> {
                    h.setBearerAuth(tokenRegistry.getToken(managerId));
                    h.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String cfApiEndpoint(String managerId) {
        String ep = cfApiEndpoints.get(managerId);
        if (ep == null) throw new IllegalArgumentException("No CF API endpoint for: " + managerId);
        return ep;
    }

    public record ServiceKeyCredentials(String host, int port, String database,
                                         String username, String password) {}
}
