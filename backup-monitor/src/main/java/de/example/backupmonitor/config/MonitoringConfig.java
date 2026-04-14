package de.example.backupmonitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "backup-monitor")
public class MonitoringConfig {

    private EncryptionProperties encryption = new EncryptionProperties();
    private ActuatorProperties actuator = new ActuatorProperties();
    private SchedulingProperties scheduling = new SchedulingProperties();
    private RestoreTestProperties restoreTest = new RestoreTestProperties();
    private S3VerificationProperties s3Verification = new S3VerificationProperties();
    private RetentionProperties retention = new RetentionProperties();
    private List<ManagerConfig> managers = new ArrayList<>();

    @Data
    public static class EncryptionProperties {
        private String key;
        private String salt;
    }

    @Data
    public static class ActuatorProperties {
        private String username;
        private String password;
    }

    @Data
    public static class SchedulingProperties {
        private String jobCheckCron = "0 0 6 * * *";
        private String orphanCleanupCron = "0 0 4 * * *";
    }

    @Data
    public static class RestoreTestProperties {
        private boolean enabled = true;
        private int maxParallel = 2;
        private int timeoutMinutes = 45;
        private String cron = "0 0 3 * * 0";
        private List<SandboxConfig> sandboxes = new ArrayList<>();
        private List<ValidationConfig> validations = new ArrayList<>();
    }

    @Data
    public static class S3VerificationProperties {
        private boolean enabled = true;
        private int sizeTolerancePercent = 5;
        private int accessibilityCheckBytes = 1024;
    }

    @Data
    public static class RetentionProperties {
        private int jobCheckEntries = 30;
        private int restoreTestEntries = 10;
        private int s3CheckEntries = 30;
    }

    @Data
    public static class ManagerConfig {
        private String id;
        private String name;
        private String url;
        private String agentUrl;
        private String agentUsername;
        private String agentPassword;
        private CfConfig cf = new CfConfig();
        private List<ServiceInstanceConfig> instances = new ArrayList<>();
    }

    @Data
    public static class CfConfig {
        private String uaaEndpoint;
        private String cfApiEndpoint;
        private ServiceAccountConfig serviceAccount = new ServiceAccountConfig();
    }

    @Data
    public static class ServiceAccountConfig {
        private String username;
        private String password;
    }

    @Data
    public static class ServiceInstanceConfig {
        private String id;
        private String name;
    }

    @Data
    public static class SandboxConfig {
        private String instanceId;
        private String mode = "existing";
        private ExistingSandboxConfig existing = new ExistingSandboxConfig();
        private ProvisionConfig provision = new ProvisionConfig();
    }

    @Data
    public static class ExistingSandboxConfig {
        private String host;
        private int port = 5432;
        private String database;
        private String username;
        private String password;
    }

    @Data
    public static class ProvisionConfig {
        private String org;
        private String space;
        private String service = "postgresql";
        private String plan = "small";
        private String instanceNamePrefix = "restore-test-sandbox";
    }

    @Data
    public static class ValidationConfig {
        private String instanceId;
        private List<QueryConfig> queries = new ArrayList<>();
    }

    @Data
    public static class QueryConfig {
        private String description;
        private String sql;
        private long minResult = 0;
    }
}
