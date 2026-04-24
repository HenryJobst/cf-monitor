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
import org.springframework.web.client.RestClientResponseException;

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
     * Erstellt zunächst eine FileDestination (oder verwendet eine vorhandene),
     * da POST /backupPlans ein bereits in MongoDB persistiertes Objekt mit ID erwartet.
     *
     * @param schedule         Cron-Ausdruck (5-stellig, z.B. "0 2 * * *" für täglich 02:00)
     * @param retentionStyle   ALL, DAYS, FILES oder HOURS
     * @param retentionPeriod  Anzahl aufzubewahrender Einheiten (muss &gt; 0 sein)
     * @param timezone         Zeitzone für den Schedule (z.B. "Europe/Berlin", "UTC")
     */
    public Optional<BackupPlan> createBackupPlan(String managerId, String instanceId,
                                                   String schedule,
                                                   String retentionStyle,
                                                   int retentionPeriod,
                                                   String timezone,
                                                   S3FileDestination s3) {
        FileDestination fileDest = getOrCreateFileDestination(managerId, instanceId, s3).orElse(null);
        if (fileDest == null) {
            log.warn("Could not obtain file destination for instance {}, skipping plan creation", instanceId);
            return Optional.empty();
        }

        try {
            ManagerEndpoint ep = endpoints.get(managerId);

            Map<String, Object> destBody = buildDestinationPayload(s3);
            destBody.put("id", fileDest.idAsString());
            destBody.put("idAsString", fileDest.idAsString());
            destBody.put("serviceInstance", Map.of("service_instance_id", instanceId));

            Map<String, Object> body = new HashMap<>();
            body.put("serviceInstance", Map.of("service_instance_id", instanceId));
            body.put("frequency", toQuartzCron(schedule));
            body.put("retentionStyle", retentionStyle);
            body.put("retentionPeriod", retentionPeriod);
            body.put("timezone", timezone);
            body.put("fileDestination", destBody);

            BackupPlan plan = ep.restClient.post()
                    .uri(ep.url + "/backupPlans")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(BackupPlan.class);
            log.info("Created backup plan for instance {} (schedule: {})", instanceId, schedule);
            return Optional.ofNullable(plan);
        } catch (RestClientResponseException e) {
            log.warn("Failed to create backup plan for instance {}: {} — body: {}",
                    instanceId, e.getMessage(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to create backup plan for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildDestinationPayload(S3FileDestination s3) {
        Map<String, Object> dest = new HashMap<>();
        dest.put("type", "S3");
        dest.put("authKey", s3.getAuthKey());
        dest.put("authSecret", s3.getAuthSecret());
        dest.put("bucket", s3.getBucket());
        if (s3.getEndpoint() != null) dest.put("endpoint", s3.getEndpoint());
        if (s3.getRegion() != null) dest.put("region", s3.getRegion());
        return dest;
    }

    /** Wandelt einen 5-Felder-Standard-Cron ("0 2 * * *") in Quartz-Format um ("0 0 2 * * *"). */
    private String toQuartzCron(String schedule) {
        if (schedule == null) return schedule;
        String[] parts = schedule.trim().split("\\s+");
        return parts.length == 5 ? "0 " + schedule : schedule;
    }

    /**
     * Gibt eine vorhandene FileDestination für die Instanz zurück oder legt eine neue an.
     * Notwendig, da POST /backupPlans ein bereits in MongoDB gespeichertes FileDestination-Objekt (mit ID) erwartet.
     */
    private Optional<FileDestination> getOrCreateFileDestination(String managerId,
                                                                   String instanceId,
                                                                   S3FileDestination s3) {
        Optional<FileDestination> existing = getFileDestinationByInstance(managerId, instanceId);
        if (existing.isPresent()) return existing;
        return createFileDestination(managerId, instanceId, s3);
    }

    private Optional<FileDestination> getFileDestinationByInstance(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/fileDestinations/byInstance/" + instanceId + "?size=1";
            PageContent<FileDestination> page = ep.restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (page == null || page.getContent().isEmpty()) return Optional.empty();
            return Optional.of(page.getContent().get(0));
        } catch (Exception e) {
            log.warn("Failed to get file destination for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FileDestination> createFileDestination(String managerId, String instanceId,
                                                             S3FileDestination s3) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            Map<String, Object> body = new HashMap<>();
            body.put("type", "S3");
            body.put("serviceInstance", Map.of("service_instance_id", instanceId));
            body.put("authKey", s3.getAuthKey());
            body.put("authSecret", s3.getAuthSecret());
            body.put("bucket", s3.getBucket());
            if (s3.getEndpoint() != null) body.put("endpoint", s3.getEndpoint());
            if (s3.getRegion() != null) body.put("region", s3.getRegion());

            FileDestination dest = ep.restClient.post()
                    .uri(ep.url + "/fileDestinations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(FileDestination.class);
            log.info("Created file destination for instance {}", instanceId);
            return Optional.ofNullable(dest);
        } catch (RestClientResponseException e) {
            log.warn("Failed to create file destination for instance {}: {} — body: {}",
                    instanceId, e.getMessage(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to create file destination for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
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
    record FileDestination(String idAsString, String type,
                           String authKey, String authSecret,
                           String bucket, String endpoint, String region) {}

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
