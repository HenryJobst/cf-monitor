package de.example.backupmonitor.client;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.FileDestination;
import de.example.backupmonitor.model.S3FileDestination;
import de.example.backupmonitor.sandbox.SandboxConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AgentClient {

    private final Map<String, AgentEndpoint> endpoints;

    public AgentClient(MonitoringConfig config) {
        this.endpoints = config.getManagers().stream()
                .collect(Collectors.toMap(
                        MonitoringConfig.ManagerConfig::getId,
                        m -> new AgentEndpoint(
                                m.getAgentUrl(),
                                m.getAgentUsername(),
                                m.getAgentPassword())));
    }

    public String triggerRestore(String managerId, String agentJobId,
                                  FileDestination destination,
                                  String filename,
                                  SandboxConnection sandbox) {
        AgentEndpoint ep = endpoints.get(managerId);
        String url = ep.url + "/restore";

        Map<String, Object> body = new HashMap<>();
        body.put("id", agentJobId);
        body.put("compression", false);
        body.put("destination", buildDestination(destination, filename));
        body.put("restore", buildDbInfo(sandbox));

        ep.restClient.put()
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setBasicAuth(ep.username, ep.password);
                })
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Triggered restore job {} via agent for manager {}", agentJobId, managerId);
        return agentJobId;
    }

    public AgentRestoreStatus pollStatus(String managerId, String agentJobId) {
        AgentEndpoint ep = endpoints.get(managerId);
        String url = ep.url + "/restore/" + agentJobId;

        try {
            Map<?, ?> response = ep.restClient.get()
                    .uri(url)
                    .headers(h -> h.setBasicAuth(ep.username, ep.password))
                    .retrieve()
                    .body(Map.class);
            if (response == null) return AgentRestoreStatus.UNKNOWN;
            String status = String.valueOf(response.get("status"));
            return AgentRestoreStatus.fromString(status);
        } catch (Exception e) {
            log.warn("Failed to poll restore status for job {}: {}", agentJobId, e.getMessage());
            return AgentRestoreStatus.UNKNOWN;
        }
    }

    private Map<String, Object> buildDestination(FileDestination destination, String filename) {
        Map<String, Object> dest = new HashMap<>();
        dest.put("filename", filename);

        if (destination instanceof S3FileDestination s3) {
            dest.put("type", "S3");
            dest.put("bucket", s3.getBucket());
            dest.put("region", s3.getRegion());
            dest.put("authKey", s3.getAuthKey());
            dest.put("authSecret", s3.getAuthSecret());
            // Custom S3 endpoint (e.g. MinIO) is passed as authUrl
            if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
                dest.put("authUrl", s3.getEndpoint());
            }
        } else if (destination != null) {
            dest.put("type", destination.getType());
        }

        return dest;
    }

    private Map<String, Object> buildDbInfo(SandboxConnection sandbox) {
        Map<String, Object> db = new HashMap<>();
        db.put("host", sandbox.host());
        db.put("username", sandbox.username());
        db.put("password", sandbox.password());
        db.put("database", sandbox.database());
        db.put("parameters", List.of());
        return db;
    }

    public enum AgentRestoreStatus {
        SUCCEEDED, FAILED, RUNNING, TIMEOUT, UNKNOWN;

        public static AgentRestoreStatus fromString(String s) {
            if (s == null) return UNKNOWN;
            return switch (s.toLowerCase()) {
                case "success", "succeeded" -> SUCCEEDED;
                case "failed" -> FAILED;
                case "running" -> RUNNING;
                case "timeout" -> TIMEOUT;
                default -> UNKNOWN;
            };
        }
    }

    private static class AgentEndpoint {
        final String url;
        final String username;
        final String password;
        final RestClient restClient;

        AgentEndpoint(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.restClient = RestClient.builder()
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build();
        }
    }
}
