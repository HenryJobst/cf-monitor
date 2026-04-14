package de.example.backupmonitor.client;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.FileDestination;
import de.example.backupmonitor.sandbox.SandboxConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
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
        String url = ep.url + "/v2/restore_jobs";

        Map<String, Object> body = new HashMap<>();
        body.put("job_id", agentJobId);
        body.put("destination", destination);
        body.put("filename", filename);
        body.put("target_host", sandbox.host());
        body.put("target_port", sandbox.port());
        body.put("target_database", sandbox.database());
        body.put("target_username", sandbox.username());
        body.put("target_password", sandbox.password());

        ep.restClient.post()
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
        String url = ep.url + "/v2/restore_jobs/" + agentJobId;

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

    public enum AgentRestoreStatus {
        SUCCEEDED, FAILED, RUNNING, TIMEOUT, UNKNOWN;

        public static AgentRestoreStatus fromString(String s) {
            try {
                return valueOf(s.toUpperCase());
            } catch (Exception e) {
                return UNKNOWN;
            }
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
            this.restClient = RestClient.create();
        }
    }
}
