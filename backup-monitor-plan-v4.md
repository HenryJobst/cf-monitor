# Implementierungsplan: OSB Backup Monitor v4

Spring Boot 4 · Java 21 · Cloud Foundry · Prometheus · Liquibase

---

## Alle Design-Entscheidungen im Überblick

| # | Thema | Entscheidung |
|---|-------|-------------|
| 1 | Token-Verschlüsselung | AES via Spring Security Crypto, Key aus ENV `TOKEN_ENCRYPTION_KEY` |
| 2 | Lokale DB | PostgreSQL via Docker – kein H2 |
| 3 | Gauge-Aktualisierung | `AtomicDouble`/`AtomicLong` als unveränderliche Gauge-Referenz |
| 4 | Retention-Scope | Pro `(instance_id, run_type)` – JOB_CHECK und RESTORE_TEST separat konfigurierbar |
| 5 | Parallel-Provisioning | Pessimistisches `Semaphore` – ein Thread provisioniert gleichzeitig |
| 6 | CF API-Auth | Gleicher UAA-Token wie für Backup-Manager |
| 7 | Distributed Lock | ShedLock mit PostgreSQL-Backend |
| 8 | WireMock | Nur `test`-Scope – lokal werden echte Endpunkte genutzt |
| 9 | SandboxRegistry | DB-persistent – kein Doppel-Provisioning nach Neustart |
| 10 | Verwaiste Sandboxen | Scheduled Job prüft und deprovisioniert (konfigurierbar) |
| 11 | Retention N | Separate Properties für JOB_CHECK- und RESTORE_TEST-Einträge |

---

## 1. Projektstruktur

```
backup-monitor/
├── src/main/java/de/example/backupmonitor/
│   ├── BackupMonitorApplication.java
│   │
│   ├── config/
│   │   ├── MonitoringConfig.java           # @ConfigurationProperties (multi-manager)
│   │   ├── AppConfig.java                  # RestTemplate-Beans, ObjectMapper
│   │   ├── DataSourceConfig.java           # PostgreSQL Persistence-DB
│   │   ├── EncryptionConfig.java           # NEU: AES-Verschlüsselung (Spring Security Crypto)
│   │   └── ShedLockConfig.java             # NEU: ShedLock mit PostgreSQL-Backend
│   │
│   ├── auth/
│   │   ├── CfTokenService.java             # UAA OAuth2 je Manager + AES-Verschlüsselung
│   │   ├── CfTokenServiceRegistry.java     # Map<managerId, CfTokenService>
│   │   ├── BearerTokenInterceptor.java
│   │   ├── TokenRepository.java            # JPA Repository für stored_token
│   │   ├── StoredToken.java                # JPA Entity (Tokens verschlüsselt)
│   │   └── TokenResponse.java
│   │
│   ├── client/
│   │   ├── BackupManagerClient.java        # HTTP-Client Backup-Manager-API
│   │   ├── AgentClient.java                # HTTP-Client Backup-Agent-API
│   │   └── CfApiClient.java                # NEU: CF API (Provisioning + Quota) –
│   │                                       # nutzt denselben UAA-Token
│   ├── model/
│   │   ├── BackupPlan.java
│   │   ├── BackupJob.java
│   │   ├── FileDestination.java
│   │   ├── S3FileDestination.java
│   │   ├── RestoreRequest.java
│   │   ├── JobStatus.java
│   │   └── CheckResult.java
│   │
│   ├── monitor/
│   │   ├── MonitoringOrchestrator.java
│   │   ├── BackupPlanMonitor.java
│   │   ├── BackupJobMonitor.java
│   │   └── RestoreTestMonitor.java         # Parallel via ExecutorService + Semaphore
│   │
│   ├── sandbox/
│   │   ├── SandboxManager.java             # Facade: existing oder provision
│   │   ├── SandboxProvisioner.java         # CF API – mit Semaphore-Lock
│   │   ├── ResourceChecker.java            # CF Quota-Prüfung via CfApiClient
│   │   ├── SandboxRegistry.java            # DB-backed Registry
│   │   ├── SandboxRegistryRepository.java  # JPA Repository
│   │   ├── SandboxRegistryEntry.java       # JPA Entity
│   │   └── OrphanedSandboxCleaner.java     # NEU: Scheduled Job für Bereinigung
│   │
│   ├── validation/
│   │   ├── DatabaseContentChecker.java
│   │   └── QueryCheckResult.java
│   │
│   ├── metrics/
│   │   ├── MetricsPublisher.java           # AtomicDouble/AtomicLong als Gauge-Referenzen
│   │   ├── BackupMetrics.java              # Gauge-Registrierung + AtomicValue-Map
│   │   └── MetricNames.java
│   │
│   ├── persistence/
│   │   ├── MonitorRunRepository.java
│   │   ├── MonitorRun.java
│   │   ├── RestoreTestResultRepository.java
│   │   ├── RestoreTestResultEntity.java
│   │   └── RetentionCleanupJob.java        # NEU: löscht alte Einträge nach N-Regel
│   │
│   └── scheduler/
│       └── MonitoringScheduler.java        # @Scheduled + @SchedulerLock (ShedLock)
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   ├── application-cf.yml
│   └── db/changelog/
│       ├── db.changelog-master.yaml
│       └── changes/
│           ├── 001-create-stored-token.yaml
│           ├── 002-create-monitor-run.yaml
│           ├── 003-create-restore-result.yaml
│           ├── 004-create-sandbox-registry.yaml  # NEU
│           └── 005-create-shedlock.yaml           # NEU
│
└── manifest.yml
```

---

## 2. Konfiguration

### application.yml (Basis)

```yaml
spring:
  application:
    name: backup-monitor
  jpa:
    open-in-view: false
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: always

backup-monitor:
  # AES-Schlüssel für Token-Verschlüsselung
  encryption:
    key: ${TOKEN_ENCRYPTION_KEY}

  scheduling:
    job-check-cron:            ${JOB_CHECK_CRON:0 0 6 * * *}
    orphan-cleanup-cron:       ${ORPHAN_CLEANUP_CRON:0 0 4 * * *}

  restore-test:
    enabled:                   ${RESTORE_TEST_ENABLED:true}
    max-parallel:              ${RESTORE_TEST_MAX_PARALLEL:2}
    timeout-minutes:           ${RESTORE_TEST_TIMEOUT:45}
    cron:                      ${RESTORE_TEST_CRON:0 0 3 * * 0}

  # Retention: pro (instance_id, run_type)
  retention:
    job-check-entries:         ${RETENTION_JOB_CHECK:30}   # letzten 30 JOB_CHECKs
    restore-test-entries:      ${RETENTION_RESTORE_TEST:10} # letzten 10 RESTORE_TESTs
```

### application-cf.yml

```yaml
spring:
  datasource:
    url:                       ${PERSISTENCE_DB_URL}
    username:                  ${PERSISTENCE_DB_USERNAME}
    password:                  ${PERSISTENCE_DB_PASSWORD}
    driver-class-name:         org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto:                validate

backup-monitor:
  managers:
    - id:                      manager-prod-1
      name:                    "Production Manager 1"
      url:                     ${MANAGER_1_URL}
      agent-url:               ${MANAGER_1_AGENT_URL}
      agent-username:          ${MANAGER_1_AGENT_USERNAME}
      agent-password:          ${MANAGER_1_AGENT_PASSWORD}
      cf:
        uaa-endpoint:          ${MANAGER_1_UAA_ENDPOINT}
        cf-api-endpoint:       ${MANAGER_1_CF_API_ENDPOINT}  # für Provisioning + Quota
        service-account:
          username:            ${MANAGER_1_SA_USERNAME}
          password:            ${MANAGER_1_SA_PASSWORD}
      instances:
        - id:                  ${MANAGER_1_INSTANCE_1_ID}
          name:                ${MANAGER_1_INSTANCE_1_NAME:pg-prod-orders}

  restore-test:
    sandboxes:
      - instance-id:           ${MANAGER_1_INSTANCE_1_ID}
        mode:                  ${SANDBOX_1_MODE:existing}
        existing:
          host:                ${SANDBOX_1_HOST:}
          port:                ${SANDBOX_1_PORT:5432}
          database:            ${SANDBOX_1_DB:}
          username:            ${SANDBOX_1_USER:}
          password:            ${SANDBOX_1_PASSWORD:}
        provision:
          org:                 ${CF_ORG:}
          space:               ${CF_SPACE:}
          service:             ${SANDBOX_SERVICE:postgresql}
          plan:                ${SANDBOX_PLAN:small}
          instance-name-prefix: restore-test-sandbox

    validations:
      - instance-id:           ${MANAGER_1_INSTANCE_1_ID}
        queries:
          - description:       "Schema vorhanden"
            sql:               "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'"
            min-result:        1
          - description:       "Mindestanzahl Datensätze"
            sql:               "SELECT COUNT(*) FROM ${TABLE_TO_CHECK:orders}"
            min-result:        ${MIN_ROW_COUNT:100}
```

### application-local.yml

```yaml
# Lokal: echte Endpunkte (Staging/Dev-Umgebung oder VPN)
# Kein WireMock – direkte Verbindung zu realen Backup-Manager-Instanzen

spring:
  datasource:
    url:      jdbc:postgresql://localhost:5432/backup_monitor_dev
    username: backup_monitor
    password: backup_monitor
  jpa:
    hibernate:
      ddl-auto: validate

backup-monitor:
  managers:
    - id:   manager-local
      name: "Local Dev Manager"
      url:  ${MANAGER_LOCAL_URL:https://backup-manager-staging.example.com}
      agent-url:      ${AGENT_LOCAL_URL:https://backup-agent-staging.example.com}
      agent-username: ${AGENT_LOCAL_USER:admin}
      agent-password: ${AGENT_LOCAL_PASS:admin}
      cf:
        uaa-endpoint:    ${UAA_LOCAL_ENDPOINT:https://uaa.sys-staging.example.com}
        cf-api-endpoint: ${CF_API_LOCAL_ENDPOINT:https://api.sys-staging.example.com}
        service-account:
          username: ${SA_LOCAL_USERNAME}
          password: ${SA_LOCAL_PASSWORD}
      instances:
        - id:   ${LOCAL_INSTANCE_ID:test-instance-001}
          name: pg-local-test

  restore-test:
    enabled: ${RESTORE_TEST_ENABLED:false}   # lokal standardmäßig deaktiviert
    sandboxes:
      - instance-id: ${LOCAL_INSTANCE_ID:test-instance-001}
        mode: existing
        existing:
          host:     localhost
          port:     5433
          database: restore_sandbox
          username: sandbox_user
          password: sandbox_pass

  scheduling:
    job-check-cron: "*/30 * * * * *"   # alle 30s lokal

  retention:
    job-check-entries:    5
    restore-test-entries: 3

logging:
  level:
    de.example.backupmonitor:   DEBUG
    org.springframework.web.client: DEBUG
```

---

## 3. Verschlüsselung (AES via Spring Security Crypto)

### EncryptionConfig.java

```java
@Configuration
public class EncryptionConfig {

    @Value("${backup-monitor.encryption.key}")
    private String encryptionKey;

    @Bean
    public TextEncryptor tokenEncryptor() {
        // AES-256-GCM via Spring Security Crypto
        // Key muss 32 Byte Base64-codiert sein (256 Bit)
        return Encryptors.delux(encryptionKey,
            KeyGenerators.string().generateKey());
        // Für deterministischen Salt (gleicher Cipher-Text bei gleichem Input):
        // return Encryptors.text(encryptionKey, fixedSalt);
        // Für maximale Sicherheit (zufälliger Salt pro Encrypt):
        // return Encryptors.delux(encryptionKey, salt);
    }
}
```

> **Hinweis:** `Encryptors.delux()` verwendet AES-256-GCM mit zufälligem Salt – jeder Encrypt-Aufruf liefert einen anderen Cipher-Text, Decrypt funktioniert trotzdem korrekt. Das ist die empfohlene Variante.

### StoredToken.java (mit Verschlüsselung)

```java
@Entity
@Table(name = "stored_token",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stored_token_manager",
        columnNames = "manager_id"))
public class StoredToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    // Tokens werden verschlüsselt in der DB abgelegt
    // Die Entität selbst kennt nur den Cipher-Text
    // Ver-/Entschlüsselung erfolgt im CfTokenService via TextEncryptor
    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "access_token_expiry")
    private Instant accessTokenExpiry;

    @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
    private String refreshTokenEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "uaa_endpoint", nullable = false, length = 512)
    private String uaaEndpoint;

    public boolean isAccessTokenValid() {
        return accessTokenEnc != null
            && accessTokenExpiry != null
            && Instant.now().isBefore(accessTokenExpiry);
    }

    public boolean hasRefreshToken() {
        return refreshTokenEnc != null && !refreshTokenEnc.isBlank();
    }
}
```

### CfTokenService.java (mit Verschlüsselung + DB-Persist)

```java
@Slf4j
public class CfTokenService {

    private final String managerId;
    private final String uaaEndpoint;
    private final String username;
    private final String password;
    private final TokenRepository tokenRepository;
    private final TextEncryptor encryptor;
    private final RestTemplate restTemplate = new RestTemplate();

    // Lokaler In-Memory-Cache (Klartext)
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant accessTokenExpiry;

    public String getValidAccessToken() {
        if (isLocalCacheValid())    return accessToken;
        if (accessToken == null)    loadFromDatabase();
        if (isLocalCacheValid())    return accessToken;
        if (refreshToken != null) {
            try {
                return refreshAndPersist();
            } catch (Exception e) {
                log.warn("Refresh failed for manager {}: {}", managerId, e.getMessage());
                clearStoredRefreshToken();
            }
        }
        return authenticateAndPersist();
    }

    private boolean isLocalCacheValid() {
        return accessToken != null
            && accessTokenExpiry != null
            && Instant.now().isBefore(accessTokenExpiry);
    }

    private void loadFromDatabase() {
        tokenRepository.findByManagerId(managerId).ifPresent(stored -> {
            // Entschlüsseln beim Lesen aus DB
            if (stored.getAccessTokenEnc() != null)
                this.accessToken = encryptor.decrypt(stored.getAccessTokenEnc());
            if (stored.getRefreshTokenEnc() != null)
                this.refreshToken = encryptor.decrypt(stored.getRefreshTokenEnc());
            this.accessTokenExpiry = stored.getAccessTokenExpiry();
            log.debug("Loaded token for manager {} from DB (valid: {})",
                managerId, stored.isAccessTokenValid());
        });
    }

    private synchronized String refreshAndPersist() {
        if (isLocalCacheValid()) return accessToken;
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", this.refreshToken);
        TokenResponse r = postToUaa(params);
        updateCache(r);
        persistTokens();
        return accessToken;
    }

    private synchronized String authenticateAndPersist() {
        if (isLocalCacheValid()) return accessToken;
        log.info("Authenticating manager {} at {}", managerId, uaaEndpoint);
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "password");
        params.add("username", username);
        params.add("password", password);
        TokenResponse r = postToUaa(params);
        updateCache(r);
        persistTokens();
        return accessToken;
    }

    private void updateCache(TokenResponse r) {
        this.accessToken       = r.accessToken();
        this.refreshToken      = r.refreshToken();
        this.accessTokenExpiry = Instant.now().plusSeconds(r.expiresIn() - 300);
    }

    @Transactional
    private void persistTokens() {
        StoredToken stored = tokenRepository
            .findByManagerId(managerId)
            .orElse(new StoredToken());
        stored.setManagerId(managerId);
        stored.setUaaEndpoint(uaaEndpoint);
        // Verschlüsseln beim Schreiben in DB
        stored.setAccessTokenEnc(encryptor.encrypt(accessToken));
        stored.setRefreshTokenEnc(encryptor.encrypt(refreshToken));
        stored.setAccessTokenExpiry(accessTokenExpiry);
        stored.setUpdatedAt(Instant.now());
        tokenRepository.save(stored);
    }

    @Transactional
    private void clearStoredRefreshToken() {
        tokenRepository.findByManagerId(managerId).ifPresent(t -> {
            t.setRefreshTokenEnc(null);
            tokenRepository.save(t);
        });
        this.refreshToken = null;
    }

    private TokenResponse postToUaa(MultiValueMap<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth("cf", "");
        return restTemplate.postForObject(
            uaaEndpoint + "/oauth/token",
            new HttpEntity<>(params, headers),
            TokenResponse.class);
    }
}
```

---

## 4. CF API Client (Quota + Provisioning, gleicher Token)

```java
@Service
public class CfApiClient {

    // Nutzt denselben UAA-Token wie der Backup-Manager-Client
    // (gleicher CfTokenServiceRegistry-Eintrag je Manager)
    private final CfTokenServiceRegistry tokenRegistry;

    // CF API v3: Quota für eine Org abrufen
    public OrgUsage getOrgUsage(String managerId, String orgGuid) {
        String url = cfApiEndpoint(managerId) + "/v3/organizations/"
            + orgGuid + "/usage_summary";
        return get(managerId, url, OrgUsage.class);
    }

    public OrgQuota getOrgQuota(String managerId, String orgGuid) {
        String url = cfApiEndpoint(managerId) + "/v3/organizations/" + orgGuid;
        return get(managerId, url, OrgQuota.class);
    }

    // CF API v3: Service Instance anlegen
    public void createServiceInstance(String managerId, String name,
            String service, String plan, String spaceGuid) {
        String url = cfApiEndpoint(managerId) + "/v3/service_instances";
        Map<String, Object> body = Map.of(
            "name", name,
            "type", "managed",
            "relationships", Map.of(
                "space", Map.of("data", Map.of("guid", spaceGuid)),
                "service_plan", Map.of("data", Map.of(
                    "guid", resolvePlanGuid(managerId, service, plan)))));
        post(managerId, url, body);
    }

    // Polling bis Service Instance den State "available" erreicht
    public void pollUntilReady(String managerId, String instanceName,
                                Duration timeout) {
        // GET /v3/service_instances?names={name}&fields[space]=name
        // bis last_operation.state == "succeeded"
    }

    // Service Key anlegen und Credentials zurückgeben
    public ServiceKeyCredentials createServiceKey(String managerId,
            String instanceGuid, String keyName) {
        String url = cfApiEndpoint(managerId) + "/v3/service_credential_bindings";
        // POST → credentials.host, .port, .database, .username, .password
    }

    private <T> T get(String managerId, String url, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenRegistry.getToken(managerId));
        return restTemplate.exchange(
            url, HttpMethod.GET,
            new HttpEntity<>(headers), type).getBody();
    }

    private void post(String managerId, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenRegistry.getToken(managerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Void.class);
    }
}
```

---

## 5. ShedLock (Distributed Lock)

### ShedLockConfig.java

```java
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()  // DB-Zeit statt App-Zeit → konsistent über Instanzen
                .build());
    }
}
```

### MonitoringScheduler.java (mit ShedLock)

```java
@Component
@Slf4j
public class MonitoringScheduler {

    private final MonitoringOrchestrator orchestrator;
    private final OrphanedSandboxCleaner sandboxCleaner;
    private final RetentionCleanupJob retentionCleanup;

    @Scheduled(cron = "${backup-monitor.scheduling.job-check-cron}")
    @SchedulerLock(name = "job_check",
        lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void runJobCheck() {
        log.info("Starting job check (ShedLock acquired)");
        orchestrator.runJobChecks();
    }

    @Scheduled(cron = "${backup-monitor.restore-test.cron}")
    @SchedulerLock(name = "restore_test",
        lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
    public void runRestoreTest() {
        log.info("Starting restore test (ShedLock acquired)");
        orchestrator.runRestoreTests();
    }

    @Scheduled(cron = "${backup-monitor.scheduling.orphan-cleanup-cron}")
    @SchedulerLock(name = "orphan_cleanup",
        lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runOrphanCleanup() {
        log.info("Starting orphaned sandbox cleanup");
        sandboxCleaner.cleanupOrphans();
    }

    // Retention-Cleanup läuft nach jedem Job-Check automatisch
    // (aufgerufen vom Orchestrator, nicht separat geplant)
}
```

---

## 6. Metrics (AtomicDouble/AtomicLong als Gauge-Referenzen)

```java
@Component
public class MetricsPublisher {

    private final MeterRegistry registry;

    // Gauge-Werte: Map<metricKey, AtomicDouble>
    // Key = "metricName:managerId:instanceId"
    private final ConcurrentHashMap<String, AtomicDouble> gaugeValues =
        new ConcurrentHashMap<>();

    private Tags instanceTags(String managerId, String instanceId,
                               String instanceName) {
        return Tags.of(
            "manager_id",    managerId,
            "instance_id",   instanceId,
            "instance_name", instanceName != null ? instanceName : instanceId);
    }

    // Gauge einmalig registrieren, AtomicDouble-Referenz für Updates
    private AtomicDouble getOrRegisterGauge(String metricName,
            Tags tags, String description) {
        String key = metricName + ":" + tags.stream()
            .map(t -> t.getKey() + "=" + t.getValue())
            .collect(Collectors.joining(":"));

        return gaugeValues.computeIfAbsent(key, k -> {
            AtomicDouble value = new AtomicDouble(0.0);
            Gauge.builder(metricName, value, AtomicDouble::get)
                .tags(tags)
                .description(description)
                .register(registry);
            return value;
        });
    }

    public void recordPlanStatus(String managerId, String instanceId,
                                  String instanceName, BackupPlan plan) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        getOrRegisterGauge(MetricNames.PLAN_ACTIVE, tags,
            "1 = plan exists and active, 0 = missing or paused")
            .set(plan != null && !plan.isPaused() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.PLAN_PAUSED, tags,
            "1 = plan is paused")
            .set(plan != null && plan.isPaused() ? 1.0 : 0.0);
    }

    public void recordJobResult(String managerId, String instanceId,
                                 String instanceName, BackupJob job) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        getOrRegisterGauge(MetricNames.JOB_LAST_STATUS, tags,
            "1=SUCCEEDED, 0=otherwise")
            .set(job != null && JobStatus.SUCCEEDED == job.getStatus() ? 1.0 : 0.0);
        getOrRegisterGauge(MetricNames.JOB_LAST_AGE_HOURS, tags,
            "Age of last backup in hours")
            .set(job != null && job.getEndDate() != null
                ? Duration.between(job.getEndDate(), Instant.now()).toHours()
                : -1.0);
        getOrRegisterGauge(MetricNames.JOB_LAST_FILESIZE, tags,
            "Size of last backup file in bytes")
            .set(resolveFilesize(job));
    }

    public void recordRestoreResult(String managerId, String instanceId,
                                     String instanceName,
                                     RestoreTestResult result) {
        Tags tags = instanceTags(managerId, instanceId, instanceName);
        double statusVal = switch (result.status()) {
            case OK           ->  1.0;
            case SKIPPED      -> -1.0;
            case NO_RESOURCES -> -2.0;
            default           ->  0.0;
        };
        getOrRegisterGauge(MetricNames.RESTORE_LAST_STATUS, tags,
            "1=ok, 0=failed, -1=skipped, -2=no_resources")
            .set(statusVal);
        getOrRegisterGauge(MetricNames.RESTORE_LAST_DURATION_SEC, tags,
            "Duration of last restore test in seconds")
            .set(result.durationSeconds());
        getOrRegisterGauge(MetricNames.RESTORE_VALIDATION_PASSED, tags,
            "1 = all validation queries passed")
            .set(result.allValidationsPassed() ? 1.0 : 0.0);
    }

    public void incrementJobSuccess(String managerId, String instanceId,
                                     String instanceName) {
        registry.counter(MetricNames.JOB_SUCCESS_TOTAL,
            instanceTags(managerId, instanceId, instanceName)).increment();
    }

    public void incrementJobFailure(String managerId, String instanceId,
                                     String instanceName) {
        registry.counter(MetricNames.JOB_FAILURE_TOTAL,
            instanceTags(managerId, instanceId, instanceName)).increment();
    }

    public void recordMonitorRun(String managerId, Instant runTime) {
        gaugeValues.computeIfAbsent(
            MetricNames.MONITOR_LAST_RUN + ":" + managerId, k -> {
                AtomicDouble v = new AtomicDouble(runTime.getEpochSecond());
                Gauge.builder(MetricNames.MONITOR_LAST_RUN, v, AtomicDouble::get)
                    .tag("manager_id", managerId)
                    .description("Timestamp of last monitor run")
                    .register(registry);
                return v;
            }).set(runTime.getEpochSecond());
    }

    private double resolveFilesize(BackupJob job) {
        if (job == null || job.getAgentExecutionReponses() == null) return 0.0;
        return job.getAgentExecutionReponses().values().stream()
            .mapToLong(r -> r.getFilesizeBytes() != null ? r.getFilesizeBytes() : 0L)
            .sum();
    }
}
```

---

## 7. SandboxRegistry (DB-backed)

### SandboxRegistryEntry.java

```java
@Entity
@Table(name = "sandbox_registry")
public class SandboxRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false, length = 128, unique = true)
    private String instanceId;

    @Column(name = "cf_service_instance_name", length = 256)
    private String cfServiceInstanceName;   // null wenn mode=EXISTING

    @Column(name = "cf_service_instance_guid", length = 128)
    private String cfServiceInstanceGuid;

    @Column(name = "sandbox_host", nullable = false, length = 512)
    private String sandboxHost;

    @Column(name = "sandbox_port", nullable = false)
    private int sandboxPort;

    @Column(name = "sandbox_database", nullable = false, length = 256)
    private String sandboxDatabase;

    @Column(name = "sandbox_username", length = 256)
    private String sandboxUsername;

    @Column(name = "sandbox_password_enc", columnDefinition = "TEXT")
    private String sandboxPasswordEnc;      // verschlüsselt

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private SandboxMode mode;               // EXISTING | PROVISION

    @Column(name = "provisioned_at")
    private Instant provisionedAt;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;
}
```

### OrphanedSandboxCleaner.java

```java
@Component
@Slf4j
public class OrphanedSandboxCleaner {

    private final SandboxRegistryRepository registryRepo;
    private final CfApiClient cfApiClient;
    private final MonitoringConfig config;

    // Findet DB-Einträge, deren instance_id nicht mehr in der Konfiguration ist
    public void cleanupOrphans() {
        Set<String> configuredInstanceIds = config.getManagers().stream()
            .flatMap(m -> m.instances().stream())
            .map(MonitoringConfig.ServiceInstanceConfig::id)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());

        List<SandboxRegistryEntry> orphans = registryRepo.findAll().stream()
            .filter(e -> !configuredInstanceIds.contains(e.getInstanceId()))
            .filter(e -> e.getMode() == SandboxMode.PROVISION) // nur provisionierte
            .toList();

        for (SandboxRegistryEntry orphan : orphans) {
            try {
                log.info("Deprovisioning orphaned sandbox {} (CF instance: {})",
                    orphan.getInstanceId(), orphan.getCfServiceInstanceName());
                cfApiClient.deleteServiceInstance(
                    orphan.getManagerId(),
                    orphan.getCfServiceInstanceGuid());
                registryRepo.delete(orphan);
                log.info("Successfully deprovisioned orphaned sandbox {}",
                    orphan.getInstanceId());
            } catch (Exception e) {
                log.error("Failed to deprovision orphaned sandbox {}: {}",
                    orphan.getInstanceId(), e.getMessage());
                // kein Abbruch – nächsten Orphan versuchen
            }
        }
    }
}
```

---

## 8. Sandbox-Provisioning mit Semaphore

```java
@Component
@Slf4j
public class SandboxProvisioner {

    private final CfApiClient cfApiClient;
    private final SandboxRegistry registry;
    private final TextEncryptor encryptor;
    private final MonitoringConfig config;

    // Semaphore: max. 1 Thread provisioniert gleichzeitig
    private final Semaphore provisioningSemaphore = new Semaphore(1, true);

    public SandboxConnection provision(String managerId, String instanceId,
                                        ProvisionConfig cfg) {
        boolean acquired = false;
        try {
            log.debug("Waiting for provisioning semaphore for instance {}",
                instanceId);
            provisioningSemaphore.acquire();
            acquired = true;
            log.info("Provisioning sandbox for instance {}", instanceId);

            // Erneute Prüfung nach Lock-Erwerb (anderer Thread könnte
            // inzwischen provisioniert haben)
            Optional<SandboxConnection> existing =
                registry.findExisting(instanceId);
            if (existing.isPresent()) {
                log.debug("Sandbox already provisioned by another thread");
                return existing.get();
            }

            // Ressourcencheck nach Lock-Erwerb (serialisiert)
            ResourceCheckResult resources = resourceChecker.check(
                managerId, cfg);
            if (!resources.hasSufficientQuota()) {
                throw new InsufficientResourcesException(
                    resources.getReason());
            }

            // CF Service Instance anlegen
            String instanceName = cfg.instanceNamePrefix()
                + "-" + instanceId.substring(0, 8);
            cfApiClient.createServiceInstance(
                managerId, instanceName,
                cfg.service(), cfg.plan(), cfg.spaceGuid());
            cfApiClient.pollUntilReady(managerId, instanceName,
                Duration.ofMinutes(10));

            // Service Key für Credentials
            ServiceKeyCredentials creds = cfApiClient.createServiceKey(
                managerId, instanceName, instanceName + "-key");

            // In Registry persistieren
            SandboxConnection conn = new SandboxConnection(
                creds.host(), creds.port(), creds.database(),
                creds.username(), creds.password(),
                instanceName, true);
            registry.register(managerId, instanceId, conn, encryptor);
            return conn;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Provisioning interrupted", e);
        } finally {
            if (acquired) provisioningSemaphore.release();
        }
    }
}
```

---

## 9. Retention-Cleanup

```java
@Component
@Transactional
public class RetentionCleanupJob {

    private final MonitorRunRepository monitorRunRepo;
    private final RestoreTestResultRepository restoreResultRepo;
    private final MonitoringConfig config;

    // Wird nach jedem Orchestrator-Lauf aufgerufen
    // Löscht pro (instance_id, run_type) alle Einträge
    // die über den konfigurierten N-Wert hinausgehen
    public void cleanup() {
        int jobCheckN    = config.getRetention().getJobCheckEntries();
        int restoreTestN = config.getRetention().getRestoreTestEntries();

        monitorRunRepo.deleteOldEntriesPerInstanceAndType(
            RunType.JOB_CHECK, jobCheckN);
        monitorRunRepo.deleteOldEntriesPerInstanceAndType(
            RunType.RESTORE_TEST, restoreTestN);
    }
}
```

```java
// MonitorRunRepository.java
public interface MonitorRunRepository extends JpaRepository<MonitorRun, Long> {

    // Native Query: löscht alle Einträge die nicht zu den letzten N gehören
    // partitioniert nach (manager_id, instance_id, run_type)
    @Modifying
    @Query(nativeQuery = true, value = """
        DELETE FROM monitor_run
        WHERE id IN (
            SELECT id FROM (
                SELECT id,
                       ROW_NUMBER() OVER (
                           PARTITION BY manager_id, instance_id, run_type
                           ORDER BY started_at DESC
                       ) AS rn
                FROM monitor_run
                WHERE run_type = :runType
            ) ranked
            WHERE rn > :keepN
        )
        """)
    void deleteOldEntriesPerInstanceAndType(
        @Param("runType") String runType,
        @Param("keepN") int keepN);

    List<MonitorRun> findLatestPerInstance();
}
```

---

## 10. Liquibase Changelogs (YAML)

### db.changelog-master.yaml

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/changes/001-create-stored-token.yaml
  - include:
      file: db/changelog/changes/002-create-monitor-run.yaml
  - include:
      file: db/changelog/changes/003-create-restore-result.yaml
  - include:
      file: db/changelog/changes/004-create-sandbox-registry.yaml
  - include:
      file: db/changelog/changes/005-create-shedlock.yaml
```

### 001-create-stored-token.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-stored-token
      author: backup-monitor
      comment: "OAuth2-Token-Speicher (AES-verschlüsselt) je Manager-Instanz"
      changes:
        - createTable:
            tableName: stored_token
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_stored_token
              - column:
                  name: manager_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: uaa_endpoint
                  type: VARCHAR(512)
                  constraints:
                    nullable: false
              - column:
                  name: access_token_enc
                  type: TEXT
                  remarks: "AES-verschlüsselter Access Token"
              - column:
                  name: access_token_expiry
                  type: TIMESTAMP WITH TIME ZONE
              - column:
                  name: refresh_token_enc
                  type: TEXT
                  remarks: "AES-verschlüsselter Refresh Token"
              - column:
                  name: updated_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
        - addUniqueConstraint:
            tableName: stored_token
            columnNames: manager_id
            constraintName: uq_stored_token_manager
        - createIndex:
            tableName: stored_token
            indexName: idx_stored_token_manager_id
            columns:
              - column:
                  name: manager_id
```

### 002-create-monitor-run.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-create-monitor-run
      author: backup-monitor
      comment: "Prüflauf-Ergebnisse, Retention pro (instance_id, run_type)"
      changes:
        - createTable:
            tableName: monitor_run
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_monitor_run
              - column:
                  name: manager_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: instance_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: instance_name
                  type: VARCHAR(256)
              - column:
                  name: run_type
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
                  remarks: "JOB_CHECK | RESTORE_TEST"
              - column:
                  name: started_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: finished_at
                  type: TIMESTAMP WITH TIME ZONE
              - column:
                  name: status
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
                  remarks: "OK | FAILED | SKIPPED | ERROR | RUNNING"
              - column:
                  name: details
                  type: JSONB
              - column:
                  name: error_message
                  type: TEXT
        - addCheckConstraint:
            tableName: monitor_run
            constraintName: chk_monitor_run_type
            constraintBody: "run_type IN ('JOB_CHECK', 'RESTORE_TEST')"
        - addCheckConstraint:
            tableName: monitor_run
            constraintName: chk_monitor_run_status
            constraintBody: "status IN ('OK', 'FAILED', 'SKIPPED', 'ERROR', 'RUNNING')"
        - createIndex:
            tableName: monitor_run
            indexName: idx_monitor_run_instance_type_time
            columns:
              - column:
                  name: manager_id
              - column:
                  name: instance_id
              - column:
                  name: run_type
              - column:
                  name: started_at
                  descending: true
```

### 003-create-restore-result.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 003-create-restore-result
      author: backup-monitor
      comment: "Detaillierte Restore-Testergebnisse"
      changes:
        - createTable:
            tableName: restore_test_result
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_restore_test_result
              - column:
                  name: monitor_run_id
                  type: BIGINT
                  constraints:
                    nullable: false
                    foreignKeyName: fk_restore_result_monitor_run
                    references: monitor_run(id)
              - column:
                  name: instance_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: backup_job_id
                  type: VARCHAR(256)
              - column:
                  name: backup_file_name
                  type: VARCHAR(512)
              - column:
                  name: sandbox_mode
                  type: VARCHAR(32)
                  remarks: "EXISTING | PROVISION"
              - column:
                  name: restore_status
                  type: VARCHAR(32)
                  remarks: "SUCCEEDED | FAILED | TIMEOUT | NO_RESOURCES | SKIPPED"
              - column:
                  name: duration_seconds
                  type: INTEGER
              - column:
                  name: validation_results
                  type: JSONB
              - column:
                  name: all_validations_passed
                  type: BOOLEAN
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
                  defaultValueComputed: NOW()
        - addCheckConstraint:
            tableName: restore_test_result
            constraintName: chk_restore_status
            constraintBody: "restore_status IN ('SUCCEEDED','FAILED','TIMEOUT','NO_RESOURCES','SKIPPED')"
        - createIndex:
            tableName: restore_test_result
            indexName: idx_restore_result_instance_time
            columns:
              - column:
                  name: instance_id
              - column:
                  name: created_at
                  descending: true
```

### 004-create-sandbox-registry.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-create-sandbox-registry
      author: backup-monitor
      comment: "Persistente Registry für provisionierte Sandbox-CF-Service-Instances"
      changes:
        - createTable:
            tableName: sandbox_registry
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_sandbox_registry
              - column:
                  name: instance_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: manager_id
                  type: VARCHAR(128)
                  constraints:
                    nullable: false
              - column:
                  name: mode
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
                  remarks: "EXISTING | PROVISION"
              - column:
                  name: cf_service_instance_name
                  type: VARCHAR(256)
                  remarks: "null wenn mode=EXISTING"
              - column:
                  name: cf_service_instance_guid
                  type: VARCHAR(128)
              - column:
                  name: sandbox_host
                  type: VARCHAR(512)
                  constraints:
                    nullable: false
              - column:
                  name: sandbox_port
                  type: INTEGER
                  constraints:
                    nullable: false
              - column:
                  name: sandbox_database
                  type: VARCHAR(256)
                  constraints:
                    nullable: false
              - column:
                  name: sandbox_username
                  type: VARCHAR(256)
              - column:
                  name: sandbox_password_enc
                  type: TEXT
                  remarks: "AES-verschlüsseltes Passwort"
              - column:
                  name: provisioned_at
                  type: TIMESTAMP WITH TIME ZONE
        - addUniqueConstraint:
            tableName: sandbox_registry
            columnNames: instance_id
            constraintName: uq_sandbox_registry_instance
        - addCheckConstraint:
            tableName: sandbox_registry
            constraintName: chk_sandbox_mode
            constraintBody: "mode IN ('EXISTING', 'PROVISION')"
        - createIndex:
            tableName: sandbox_registry
            indexName: idx_sandbox_registry_instance
            columns:
              - column:
                  name: instance_id
```

### 005-create-shedlock.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 005-create-shedlock
      author: backup-monitor
      comment: "ShedLock-Tabelle für Distributed Locking der Scheduled Jobs"
      changes:
        - createTable:
            tableName: shedlock
            columns:
              - column:
                  name: name
                  type: VARCHAR(64)
                  constraints:
                    nullable: false
                    primaryKey: true
                    primaryKeyName: pk_shedlock
              - column:
                  name: lock_until
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: locked_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: locked_by
                  type: VARCHAR(255)
                  constraints:
                    nullable: false
```

---

## 11. Dependencies (pom.xml)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.0</version>
</parent>

<dependencies>
    <!-- Web / REST -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Prometheus / Micrometer -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Persistence -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>

    <!-- Liquibase -->
    <dependency>
        <groupId>org.liquibase</groupId>
        <artifactId>liquibase-core</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- AES-Verschlüsselung (Spring Security Crypto) -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
    </dependency>

    <!-- ShedLock Distributed Locking -->
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
        <version>6.x</version>
    </dependency>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
        <version>6.x</version>
    </dependency>

    <!-- Tests -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>3.x</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 12. Lokaler Start

```bash
# Persistence-DB
docker run -d --name backup-monitor-db \
  -e POSTGRES_DB=backup_monitor_dev \
  -e POSTGRES_USER=backup_monitor \
  -e POSTGRES_PASSWORD=backup_monitor \
  -p 5432:5432 postgres:16

# Sandbox-DB (für Restore-Tests lokal, falls aktiviert)
docker run -d --name backup-monitor-sandbox \
  -e POSTGRES_DB=restore_sandbox \
  -e POSTGRES_USER=sandbox_user \
  -e POSTGRES_PASSWORD=sandbox_pass \
  -p 5433:5432 postgres:16

# App starten (echte Staging-Endpunkte via VPN/SSH-Tunnel)
export TOKEN_ENCRYPTION_KEY="base64-encodierter-32-byte-key"
export SA_LOCAL_USERNAME="sa-user@staging"
export SA_LOCAL_PASSWORD="staging-password"
export MANAGER_LOCAL_URL="https://backup-manager-staging.example.com"
export UAA_LOCAL_ENDPOINT="https://uaa.sys-staging.example.com"
export CF_API_LOCAL_ENDPOINT="https://api.sys-staging.example.com"
export LOCAL_INSTANCE_ID="staging-instance-guid"

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

---

## 13. CF Deployment (manifest.yml)

```yaml
applications:
- name: backup-monitor
  buildpacks:
  - java_buildpack
  memory: 512M
  instances: 1               # ShedLock schützt gegen kurzzeitig 2 Instanzen
  health-check-type: http
  health-check-http-endpoint: /actuator/health
  env:
    SPRING_PROFILES_ACTIVE:    cf
    TOKEN_ENCRYPTION_KEY:      ((token_encryption_key))

    # Persistence-DB
    PERSISTENCE_DB_URL:        jdbc:postgresql://pg-host:5432/backup_monitor
    PERSISTENCE_DB_USERNAME:   backup_monitor
    PERSISTENCE_DB_PASSWORD:   ((persistence_db_password))

    # Manager 1
    MANAGER_1_URL:             https://backup-manager-1.apps.example.com
    MANAGER_1_AGENT_URL:       https://backup-agent-1.apps.internal
    MANAGER_1_AGENT_USERNAME:  admin
    MANAGER_1_AGENT_PASSWORD:  ((agent_1_password))
    MANAGER_1_UAA_ENDPOINT:    https://uaa.sys.example.com
    MANAGER_1_CF_API_ENDPOINT: https://api.sys.example.com
    MANAGER_1_SA_USERNAME:     backup-monitor-sa
    MANAGER_1_SA_PASSWORD:     ((sa_1_password))
    MANAGER_1_INSTANCE_1_ID:   xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    MANAGER_1_INSTANCE_1_NAME: pg-prod-orders

    # Sandbox
    SANDBOX_1_MODE:            existing
    SANDBOX_1_HOST:            sandbox-pg.apps.internal
    SANDBOX_1_DB:              restore_test
    SANDBOX_1_USER:            restore_user
    SANDBOX_1_PASSWORD:        ((sandbox_1_password))

    # Zeitplan
    JOB_CHECK_CRON:            "0 0 6 * * *"
    RESTORE_TEST_CRON:         "0 0 3 * * 0"
    ORPHAN_CLEANUP_CRON:       "0 0 4 * * *"

    # Retention
    RETENTION_JOB_CHECK:       "30"
    RETENTION_RESTORE_TEST:    "10"

    # Validierung
    TABLE_TO_CHECK:            orders
    MIN_ROW_COUNT:             "500"
```

---

## 14. Implementierungsreihenfolge

| Phase | Aufgaben | Ergebnis |
|-------|----------|---------|
| **1** | Projektstruktur, `pom.xml`, Liquibase-Changelogs 001–005 | Schema komplett angelegt |
| **2** | `application.yml` + `-local` + `-cf`, Profile-Strategie | Beide Profile starten |
| **3** | `EncryptionConfig`, `TextEncryptor`-Bean | AES-Verschlüsselung verfügbar |
| **4** | `StoredToken`, `TokenRepository`, `CfTokenService` mit Encrypt/Decrypt | Token-Lifecycle mit DB-Persist |
| **5** | `CfTokenServiceRegistry`, `BearerTokenInterceptor` | Multi-Manager-Auth |
| **6** | `BackupManagerClient`, `AgentClient`, `CfApiClient`, DTOs | Alle HTTP-Clients funktionieren |
| **7** | `BackupPlanMonitor`, `BackupJobMonitor` | Plan + Job-Prüfung |
| **8** | `MetricsPublisher` mit `AtomicDouble`-Gauges, `MetricNames` | `/actuator/prometheus` liefert korrekte Werte |
| **9** | `ShedLockConfig`, `MonitoringScheduler` mit `@SchedulerLock` | Kein Doppellauf bei 2 Instanzen |
| **10** | `MonitorRunRepository`, `MonitoringOrchestrator`, `RetentionCleanupJob` | Ergebnisse persistiert, Retention aktiv |
| **11** | `MetricsInitializer` (Restore aus DB beim Start) | Metriken sofort nach Neustart |
| **12** | `SandboxRegistryEntry`, `SandboxRegistry` (DB-backed) | Sandbox-Zustand persistent |
| **13** | `ResourceChecker`, `SandboxProvisioner` mit Semaphore, `SandboxManager` | Provisioning sicher + serialisiert |
| **14** | `OrphanedSandboxCleaner` (Scheduled) | Verwaiste Sandboxen werden bereinigt |
| **15** | `RestoreTestMonitor` (parallel), `DatabaseContentChecker` | Restore-Tests aktiv |
| **16** | Integrationstests (Testcontainers PG, WireMock test-scope) | Testsuite grün |
| **17** | CF-Deployment, Service Account anlegen, Prometheus-Regeln | Live in Produktion |
