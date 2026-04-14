package de.example.backupmonitor.client;

import de.example.backupmonitor.auth.BearerTokenInterceptor;
import de.example.backupmonitor.auth.CfTokenServiceRegistry;
import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.BackupPlan;
import de.example.backupmonitor.model.JobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
            String url = ep.url + "/v2/service_instances/" + instanceId + "/backup_plans";
            List<BackupPlan> plans = ep.restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    new ParameterizedTypeReference<List<BackupPlan>>() {}).getBody();
            if (plans == null || plans.isEmpty()) return Optional.empty();
            return Optional.of(plans.get(0));
        } catch (Exception e) {
            log.warn("Failed to get backup plan for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BackupJob> getLatestBackupJob(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/v2/service_instances/" + instanceId
                    + "/backup_jobs?status=SUCCEEDED&limit=1";
            List<BackupJob> jobs = ep.restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    new ParameterizedTypeReference<List<BackupJob>>() {}).getBody();
            if (jobs == null || jobs.isEmpty()) return Optional.empty();
            return Optional.of(jobs.get(0));
        } catch (Exception e) {
            log.warn("Failed to get latest backup job for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BackupJob> getLatestJob(String managerId, String instanceId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/v2/service_instances/" + instanceId + "/backup_jobs?limit=1";
            List<BackupJob> jobs = ep.restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    new ParameterizedTypeReference<List<BackupJob>>() {}).getBody();
            if (jobs == null || jobs.isEmpty()) return Optional.empty();
            return Optional.of(jobs.get(0));
        } catch (Exception e) {
            log.warn("Failed to get latest job for instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BackupJob> getJobById(String managerId, String jobId) {
        try {
            ManagerEndpoint ep = endpoints.get(managerId);
            String url = ep.url + "/v2/backup_jobs/" + jobId;
            BackupJob job = ep.restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    BackupJob.class).getBody();
            return Optional.ofNullable(job);
        } catch (Exception e) {
            log.warn("Failed to get job {} : {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private static class ManagerEndpoint {
        final String url;
        final RestTemplate restTemplate;

        ManagerEndpoint(String url, String managerId, CfTokenServiceRegistry tokenRegistry) {
            this.url = url;
            this.restTemplate = new RestTemplate();
            this.restTemplate.getInterceptors().add(
                    new BearerTokenInterceptor(managerId, tokenRegistry));
        }
    }
}
