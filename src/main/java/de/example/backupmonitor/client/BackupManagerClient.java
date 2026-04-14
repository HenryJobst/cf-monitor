package de.example.backupmonitor.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.example.backupmonitor.auth.BearerTokenInterceptor;
import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.S3FileDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class BackupManagerClient {

    private final Map<String, ManagerEndpoint> endpoints;

    public BackupManagerClient(MonitoringConfig config, CfTokenServiceRegistry tokenRegistry) {
        this.endpoints = config.getManagers().stream()
                .collect(Collectors.toMap(
                        MonitoringConfig.ManagerConfig::getId,
                        m -> new ManagerEndpoint(m.getUrl(), m.getId(), tokenRegistry)));
    }

    public Optional<BackupPlan> getBackupPlan(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/backupPlans/byInstance/" + instanceId + "?size=1";
            PageContent<BackupPlan> page = ep.restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (page == null || page.getContent().isEmpty()) return Optional.empty();
            return Optional.of(page.getContent().get(0));
        } catch (Exception e) {
            log.warn("Failed to get backup plan for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BackupJob> getLatestBackupJob(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/backupJobs/byInstance/" + instanceId
                    + "/filtered?jobStatus=SUCCEEDED&size=1&sort=startDate,desc";
            PageContent<BackupJob> page = ep.restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (page == null || page.getContent().isEmpty()) return Optional.empty();
            return Optional.of(page.getContent().get(0));
        } catch (Exception e) {
            log.warn("Failed to get latest backup job for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BackupJob> getLatestJob(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/backupJobs/byInstance/" + instanceId
                    + "?size=1&sort=startDate,desc";
            PageContent<BackupJob> page = ep.restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (page == null || page.getContent().isEmpty()) return Optional.empty();
            return Optional.of(page.getContent().get(0));
        } catch (Exception e) {
            log.warn("Failed to get latest job for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Legt einen neuen Backup-Plan für eine Service-Instanz an.
     *
     * @param schedule Cron-Ausdruck (5-stellig, z.B. "0 2 * * *" für täglich 02:00)
     */
    public Optional<BackupPlan> createBackupPlan(String managerId, String instanceId,
                                                   String schedule,
                                                   S3FileDestination destination) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/backupPlans";

            Map<String, Object> body = new HashMap<>();
            body.put("instance_id", instanceId);
            body.put("schedule", schedule);
            body.put("destination", buildDestinationPayload(destination));

            BackupPlan plan = ep.restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(BackupPlan.class);
            log.info("Created backup plan for instance {} (schedule: {})", instanceId, schedule);
            return Optional.ofNullable(plan);
        } catch (Exception e) {
            log.warn("Failed to create backup plan for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildDestinationPayload(S3FileDestination s3) {
        Map<String, Object> dest = new HashMap<>();
        dest.put("type", "S3");
        dest.put("bucket", s3.getBucket());
        if (s3.getEndpoint() != null) dest.put("endpoint", s3.getEndpoint());
        if (s3.getRegion() != null) dest.put("region", s3.getRegion());
        dest.put("auth_key", s3.getAuthKey());
        dest.put("auth_secret", s3.getAuthSecret());
        return dest;
    }

    public Optional<BackupJob> getJobById(String managerId, String jobId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/backupJobs/" + jobId;
            BackupJob job = ep.restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(BackupJob.class);
            return Optional.ofNullable(job);
        } catch (Exception e) {
            log.warn("Failed to get job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PageContent<T> {
        private List<T> content = new ArrayList<>();

        public List<T> getContent() { return content; }
        public void setContent(List<T> content) { this.content = content; }
    }

    private static class ManagerEndpoint {
        final String url;
        final RestClient restClient;

        ManagerEndpoint(String url, String managerId, CfTokenServiceRegistry tokenRegistry) {
            this.url = url;
            this.restClient = RestClient.builder()
                    .requestInterceptor(new BearerTokenInterceptor(managerId, tokenRegistry))
                    .build();
        }
    }
}
