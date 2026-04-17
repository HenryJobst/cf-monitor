package de.example.backupmonitor.client;

import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.S3FileDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Sucht managed Service-Instances im angegebenen Space, deren Service-Offering
     * dem konfigurierten S3-Label entspricht.
     * Führt zwei Requests aus: erst Plan-GUIDs für das Offering ermitteln,
     * dann Instanzen im Space nach diesen Plans filtern.
     */
    @SuppressWarnings("unchecked")
    public List<S3ServiceCandidate> findServiceInstancesByOffering(
            String managerId, String spaceGuid, String serviceOfferingLabel) {
        try {
            // Schritt 1: Plan-GUIDs für das gewünschte Service-Offering ermitteln
            String plansUrl = cfApiEndpoint(managerId)
                    + "/v3/service_plans?service_offering_names=" + encode(serviceOfferingLabel);
            Map<?, ?> plansResponse = get(managerId, plansUrl, Map.class);
            if (plansResponse == null) return List.of();

            var plans = (List<?>) plansResponse.get("resources");
            if (plans == null || plans.isEmpty()) {
                log.info("Kein Service-Offering mit Label '{}' gefunden", serviceOfferingLabel);
                return List.of();
            }

            String planGuids = plans.stream()
                    .map(p -> (String) ((Map<?, ?>) p).get("guid"))
                    .collect(Collectors.joining(","));

            // Schritt 2: Instanzen im Space nach diesen Plans filtern
            String instancesUrl = cfApiEndpoint(managerId)
                    + "/v3/service_instances?space_guids=" + spaceGuid
                    + "&type=managed"
                    + "&service_plan_guids=" + planGuids;
            Map<?, ?> response = get(managerId, instancesUrl, Map.class);
            if (response == null) return List.of();

            List<S3ServiceCandidate> result = new ArrayList<>();
            var resources = (List<?>) response.get("resources");
            if (resources == null) return List.of();
            for (var res : resources) {
                var inst = (Map<?, ?>) res;
                result.add(new S3ServiceCandidate(
                        (String) inst.get("guid"),
                        (String) inst.get("name")));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list S3 service instances in space {}: {}", spaceGuid, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<S3ServiceCandidate> findServiceInstanceByName(
            String managerId, String spaceGuid, String instanceName) {
        try {
            String url = cfApiEndpoint(managerId)
                    + "/v3/service_instances?names=" + encode(instanceName)
                    + "&space_guids=" + spaceGuid
                    + "&type=managed";
            Map<?, ?> response = get(managerId, url, Map.class);
            if (response == null) return Optional.empty();
            var resources = (List<?>) response.get("resources");
            if (resources == null || resources.isEmpty()) return Optional.empty();
            var inst = (Map<?, ?>) resources.get(0);
            return Optional.of(new S3ServiceCandidate(
                    (String) inst.get("guid"),
                    (String) inst.get("name")));
        } catch (Exception e) {
            log.warn("Failed to find service instance '{}' in space {}: {}", instanceName, spaceGuid, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Liest S3-Credentials aus einem vorhandenen Service-Key oder legt einen neuen an.
     * Unterstützt gängige Credential-Feldnamen (AWS-Stil und MinIO-Stil).
     */
    @SuppressWarnings("unchecked")
    public S3FileDestination getS3Credentials(String managerId, String instanceGuid,
                                               String instanceName) {
        // Prüfe ob bereits ein Service-Key existiert
        String listUrl = cfApiEndpoint(managerId)
                + "/v3/service_credential_bindings?service_instance_guids=" + instanceGuid
                + "&type=key";
        Map<?, ?> listResponse = get(managerId, listUrl, Map.class);

        String keyGuid;
        var resources = listResponse != null ? (List<?>) listResponse.get("resources") : null;
        if (resources != null && !resources.isEmpty()) {
            keyGuid = (String) ((Map<?, ?>) resources.get(0)).get("guid");
            log.debug("Reusing existing service key {} for S3 instance {}", keyGuid, instanceName);
        } else {
            // Neuen Key anlegen
            String keyName = "cf-backup-monitor-" + instanceName + "-key";
            String createUrl = cfApiEndpoint(managerId) + "/v3/service_credential_bindings";
            Map<String, Object> body = Map.of(
                    "name", keyName,
                    "type", "key",
                    "relationships", Map.of(
                            "service_instance", Map.of("data", Map.of("guid", instanceGuid))));
            post(managerId, createUrl, body);
            log.info("Created service key '{}' for S3 instance {}", keyName, instanceName);

            // Key-GUID auflösen
            Map<?, ?> keyList = get(managerId,
                    cfApiEndpoint(managerId) + "/v3/service_credential_bindings?names=" + keyName,
                    Map.class);
            var keyResources = keyList != null ? (List<?>) keyList.get("resources") : null;
            if (keyResources == null || keyResources.isEmpty())
                throw new RuntimeException("Service key not found after creation: " + keyName);
            keyGuid = (String) ((Map<?, ?>) keyResources.get(0)).get("guid");
        }

        // Credentials abrufen
        String detailUrl = cfApiEndpoint(managerId)
                + "/v3/service_credential_bindings/" + keyGuid + "/details";
        Map<?, ?> detail = get(managerId, detailUrl, Map.class);
        if (detail == null) throw new RuntimeException("No details for service key " + keyGuid);
        Map<String, Object> creds = (Map<String, Object>) detail.get("credentials");
        if (creds == null) throw new RuntimeException("No credentials in service key " + keyGuid);

        return buildS3Destination(creds);
    }

    /**
     * Mappt CF-Service-Credentials auf S3FileDestination.
     * Unterstützt AWS-Stil (access_key_id/secret_access_key) und MinIO-Stil (access_key/secret_key).
     */
    private S3FileDestination buildS3Destination(Map<String, Object> creds) {
        S3FileDestination dest = new S3FileDestination();
        dest.setAuthKey(firstPresent(creds, "access_key_id", "access_key", "accessKeyId"));
        dest.setAuthSecret(firstPresent(creds, "secret_access_key", "secret_key", "secretAccessKey"));
        dest.setBucket(firstPresent(creds, "bucket", "default_bucket", "bucketName"));
        dest.setEndpoint(firstPresent(creds, "endpoint", "host", "uri", "url"));
        dest.setRegion(firstPresent(creds, "region", "aws_region", "location"));
        return dest;
    }

    private String firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ServiceKeyCredentials(String host, int port, String database,
                                         String username, String password) {}

    public record S3ServiceCandidate(String guid, String name) {}
}
