# Backup Monitor: S3-Verifikation (Erweiterung zu v4)

---

## Einordnung in die Prüf-Pipeline

```
Bisherige Pipeline (v4)          Neue Pipeline (v5)
─────────────────────            ────────────────────────────────────
① BackupPlanMonitor              ① BackupPlanMonitor
② BackupJobMonitor               ② BackupJobMonitor
③ RestoreTestMonitor             ③ S3VerificationService    ← NEU
                                 ④ RestoreTestMonitor
                                    └── nutzt S3-verifizierten Job
```

S3-Checks laufen nach jedem erfolgreichen Job-Check (③), **nicht** nur wöchentlich.
Der Restore-Test (④) greift auf den bereits verifizierten S3-Job zurück.

---

## 1. S3-Prüfschritte

```
Für jeden BackupJob mit Status SUCCEEDED:

  S3FileDestination aus BackupJob (bucket, endpoint, authKey, authSecret, skipSSL)
        │
        ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ S3VerificationService                                        │
  │                                                              │
  │  a) EXISTS     – HeadObject: Datei vorhanden?               │
  │  b) SIZE       – Content-Length == BackupJob.filesize?      │
  │                  (konfigurierbare Toleranz in %)            │
  │  c) ACCESSIBLE – Range-GET (erste N Bytes): lesbar?         │
  │  d) INTEGRITY  – Magic Bytes: gzip-Header (1f 8b)?          │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                  Metriken + DB-Persistenz
                             │
              (falls alles OK) ──► RestoreTestMonitor
                                   nutzt denselben BackupJob
```

---

## 2. Projektstruktur (Ergänzungen)

```
backup-monitor/src/main/java/de/example/backupmonitor/
├── s3/                                         # NEU: S3-Verifikation
│   ├── S3VerificationService.java              # Orchestriert alle S3-Checks
│   ├── S3ClientFactory.java                    # Erstellt S3-Client aus FileDestination
│   ├── S3CheckResult.java                      # Ergebnis-Model (alle 4 Checks)
│   └── S3CheckResultRepository.java            # JPA Repository
│
├── model/
│   └── S3CheckResultEntity.java               # NEU: JPA Entity
│
└── metrics/
    └── MetricNames.java                        # + neue S3-Metrik-Konstanten
```

---

## 3. Konfiguration (Ergänzung zu application.yml)

```yaml
backup-monitor:
  s3-verification:
    enabled:               ${S3_VERIFY_ENABLED:true}
    # Größentoleranz: wie viel % Abweichung ist erlaubt?
    # (Kompression/Verschlüsselung kann Dateigröße leicht variieren)
    size-tolerance-percent: ${S3_SIZE_TOLERANCE:5}
    # Anzahl Bytes für den Range-GET Accessibility-Check
    accessibility-check-bytes: ${S3_ACCESSIBILITY_BYTES:1024}
```

---

## 4. S3ClientFactory

```java
@Component
public class S3ClientFactory {

    // Erstellt einen AWS S3 Client aus den Credentials des BackupJobs.
    // Unterstützt MinIO-kompatible Endpoints und SSL-Skip.
    public S3Client createClient(S3FileDestination destination) {
        AwsCredentials credentials = AwsBasicCredentials.create(
            destination.getAuthKey(),
            destination.getAuthSecret());

        S3ClientBuilder builder = S3Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .region(resolveRegion(destination));

        // MinIO / S3-kompatibler Custom Endpoint
        if (destination.getEndpoint() != null
                && !destination.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(destination.getEndpoint()))
                   .forcePathStyle(true);  // MinIO erfordert Path-Style
        }

        // SSL-Validierung deaktivieren (nur für interne Endpoints)
        if (Boolean.TRUE.equals(destination.getSkipSSL())) {
            builder.httpClient(buildInsecureHttpClient());
        }

        return builder.build();
    }

    private Region resolveRegion(S3FileDestination dest) {
        if (dest.getRegion() != null && !dest.getRegion().isBlank())
            return Region.of(dest.getRegion());
        return Region.US_EAST_1; // Default für MinIO
    }

    private SdkHttpClient buildInsecureHttpClient() {
        // TrustManager der alle Zertifikate akzeptiert
        // NUR für interne/staging Endpoints verwenden!
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null,
                new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
            return ApacheHttpClient.builder()
                .tlsTrustManagersProvider(() -> new TrustManager[]{
                    new InsecureTrustManager()})
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure HTTP client", e);
        }
    }
}
```

---

## 5. S3VerificationService

```java
@Service
@Slf4j
public class S3VerificationService {

    private final S3ClientFactory clientFactory;
    private final S3CheckResultRepository repository;
    private final MetricsPublisher metrics;

    @Value("${backup-monitor.s3-verification.size-tolerance-percent:5}")
    private int sizeTolerance;

    @Value("${backup-monitor.s3-verification.accessibility-check-bytes:1024}")
    private int accessibilityBytes;

    // Gzip Magic Bytes: erste zwei Bytes müssen 0x1F 0x8B sein
    private static final byte[] GZIP_MAGIC = {0x1F, (byte) 0x8B};

    public S3CheckResult verify(String managerId, String instanceId,
                                 String instanceName, BackupJob job) {

        S3FileDestination dest = (S3FileDestination) job.getDestination();
        String filename = resolveFilename(job);

        S3CheckResult result = S3CheckResult.builder()
            .managerId(managerId)
            .instanceId(instanceId)
            .backupJobId(job.getIdAsString())
            .filename(filename)
            .bucket(dest.getBucket())
            .checkedAt(Instant.now())
            .build();

        try (S3Client s3 = clientFactory.createClient(dest)) {

            // ── a) EXISTS ────────────────────────────────────────────────
            HeadObjectResponse head = checkExists(s3, dest.getBucket(), filename);
            result.setExists(head != null);

            if (!result.isExists()) {
                log.warn("S3 file not found: s3://{}/{}", dest.getBucket(), filename);
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── b) SIZE ──────────────────────────────────────────────────
            long s3Size      = head.contentLength();
            long reportedSize = resolveReportedSize(job);
            result.setSizeActualBytes(s3Size);
            result.setSizeExpectedBytes(reportedSize);

            if (reportedSize > 0) {
                double deviation = Math.abs(s3Size - reportedSize)
                    / (double) reportedSize * 100.0;
                result.setSizeMatchWithinTolerance(deviation <= sizeTolerance);
                result.setSizeDeviationPercent(deviation);
            } else {
                // Keine Größe im Job → nur prüfen ob > 0
                result.setSizeMatchWithinTolerance(s3Size > 0);
            }

            // ── c) ACCESSIBLE ────────────────────────────────────────────
            byte[] firstBytes = downloadPartial(
                s3, dest.getBucket(), filename, accessibilityBytes);
            result.setAccessible(firstBytes != null && firstBytes.length > 0);

            if (!result.isAccessible()) {
                log.warn("S3 file not accessible: s3://{}/{}", dest.getBucket(), filename);
                return finalize(result, managerId, instanceId, instanceName);
            }

            // ── d) INTEGRITY (Magic Bytes) ───────────────────────────────
            if (firstBytes.length >= 2) {
                boolean gzipValid = firstBytes[0] == GZIP_MAGIC[0]
                                 && firstBytes[1] == GZIP_MAGIC[1];
                result.setMagicBytesValid(gzipValid);
                if (!gzipValid) {
                    log.warn("S3 file has unexpected magic bytes: {} {}",
                        String.format("0x%02X", firstBytes[0]),
                        String.format("0x%02X", firstBytes[1]));
                }
            }

        } catch (NoSuchKeyException e) {
            result.setExists(false);
            log.warn("S3 file not found (NoSuchKey): {}", filename);
        } catch (Exception e) {
            result.setError(e.getMessage());
            log.error("S3 verification failed for job {}: {}",
                job.getIdAsString(), e.getMessage());
        }

        return finalize(result, managerId, instanceId, instanceName);
    }

    private HeadObjectResponse checkExists(S3Client s3,
            String bucket, String key) {
        try {
            return s3.headObject(r -> r.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private byte[] downloadPartial(S3Client s3,
            String bucket, String key, int bytes) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .range("bytes=0-" + (bytes - 1))   // HTTP Range Request
                .build();
            return s3.getObject(request).readAllBytes();
        } catch (Exception e) {
            log.debug("Partial download failed: {}", e.getMessage());
            return null;
        }
    }

    private String resolveFilename(BackupJob job) {
        if (job.getFiles() != null && !job.getFiles().isEmpty())
            return job.getFiles().values().iterator().next();
        // Fallback: aus AgentExecutionResponse
        return job.getAgentExecutionReponses().values().stream()
            .map(AgentExecutionResponse::getFilename)
            .filter(f -> f != null && !f.isBlank())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No filename in job " + job.getIdAsString()));
    }

    private long resolveReportedSize(BackupJob job) {
        return job.getAgentExecutionReponses().values().stream()
            .mapToLong(r -> r.getFilesizeBytes() != null
                ? r.getFilesizeBytes() : 0L)
            .sum();
    }

    private S3CheckResult finalize(S3CheckResult result,
            String managerId, String instanceId, String instanceName) {
        result.setAllPassed(
            result.isExists()
            && result.isSizeMatchWithinTolerance()
            && result.isAccessible()
            && result.isMagicBytesValid());

        // Metriken publizieren
        metrics.recordS3CheckResult(managerId, instanceId, instanceName, result);

        // In DB persistieren
        repository.save(S3CheckResultEntity.from(result));

        return result;
    }
}
```

---

## 6. S3CheckResult Model

```java
@Builder
@Data
public class S3CheckResult {

    private String managerId;
    private String instanceId;
    private String backupJobId;
    private String filename;
    private String bucket;
    private Instant checkedAt;

    // a) EXISTS
    private boolean exists;

    // b) SIZE
    private long sizeExpectedBytes;     // aus BackupJob / AgentResponse
    private long sizeActualBytes;       // aus S3 HeadObject
    private boolean sizeMatchWithinTolerance;
    private double sizeDeviationPercent;

    // c) ACCESSIBLE
    private boolean accessible;

    // d) INTEGRITY
    private boolean magicBytesValid;

    // Gesamt
    private boolean allPassed;
    private String error;               // bei unerwarteten Exceptions

    // Für RestoreTestMonitor: ist dieser Job S3-verifiziert?
    public boolean isS3Verified() {
        return exists && accessible && sizeMatchWithinTolerance;
        // magicBytesValid ist Warning, kein harter Fehler
    }
}
```

---

## 7. Neue Prometheus-Metriken

```java
// MetricNames.java – Ergänzungen
public static final String S3_FILE_EXISTS          = "backup_s3_file_exists";
public static final String S3_SIZE_MATCH           = "backup_s3_size_match";
public static final String S3_ACCESSIBLE           = "backup_s3_accessible";
public static final String S3_MAGIC_BYTES_VALID    = "backup_s3_magic_bytes_valid";
public static final String S3_SIZE_DEVIATION_PCT   = "backup_s3_size_deviation_percent";
public static final String S3_ALL_CHECKS_PASSED    = "backup_s3_all_checks_passed";
public static final String S3_FILE_SIZE_BYTES      = "backup_s3_file_size_bytes";
```

```java
// MetricsPublisher.java – Ergänzung
public void recordS3CheckResult(String managerId, String instanceId,
                                 String instanceName, S3CheckResult result) {
    Tags tags = instanceTags(managerId, instanceId, instanceName);

    getOrRegisterGauge(MetricNames.S3_FILE_EXISTS, tags,
        "1 = backup file exists in S3")
        .set(result.isExists() ? 1.0 : 0.0);

    getOrRegisterGauge(MetricNames.S3_SIZE_MATCH, tags,
        "1 = file size within configured tolerance")
        .set(result.isSizeMatchWithinTolerance() ? 1.0 : 0.0);

    getOrRegisterGauge(MetricNames.S3_ACCESSIBLE, tags,
        "1 = file bytes downloadable via range request")
        .set(result.isAccessible() ? 1.0 : 0.0);

    getOrRegisterGauge(MetricNames.S3_MAGIC_BYTES_VALID, tags,
        "1 = file starts with valid gzip magic bytes")
        .set(result.isMagicBytesValid() ? 1.0 : 0.0);

    getOrRegisterGauge(MetricNames.S3_SIZE_DEVIATION_PCT, tags,
        "Deviation between reported and actual file size in percent")
        .set(result.getSizeDeviationPercent());

    getOrRegisterGauge(MetricNames.S3_ALL_CHECKS_PASSED, tags,
        "1 = all S3 checks passed (exists, size, accessible, magic bytes)")
        .set(result.isAllPassed() ? 1.0 : 0.0);

    getOrRegisterGauge(MetricNames.S3_FILE_SIZE_BYTES, tags,
        "Actual file size in S3 in bytes")
        .set(result.getSizeActualBytes());
}
```

### Beispiel-Prometheus-Output

```
backup_s3_file_exists{instance_id="abc-123",instance_name="pg-prod-orders",...} 1.0
backup_s3_size_match{instance_id="abc-123",...} 1.0
backup_s3_accessible{instance_id="abc-123",...} 1.0
backup_s3_magic_bytes_valid{instance_id="abc-123",...} 1.0
backup_s3_size_deviation_percent{instance_id="abc-123",...} 0.3
backup_s3_all_checks_passed{instance_id="abc-123",...} 1.0
backup_s3_file_size_bytes{instance_id="abc-123",...} 248741888.0
```

---

## 8. Integration in MonitoringOrchestrator

```java
// Aktualisierte runJobChecks()-Methode
public void runJobChecks() {
    for (ManagerConfig manager : config.getManagers()) {
        for (ServiceInstanceConfig instance : manager.instances()) {

            // ① Plan prüfen
            PlanCheckResult planResult =
                planMonitor.checkPlan(manager.id(), instance.id());
            metrics.recordPlanStatus(...);
            if (!planResult.isOk()) continue;

            // ② Letzten Job prüfen
            JobCheckResult jobResult = jobMonitor.checkLatestJob(
                manager.id(), instance.id(), planResult.getPlan());
            metrics.recordJobResult(...);

            // ③ S3-Verifikation (nur wenn Job SUCCEEDED)
            if (jobResult.isSuccess() && jobResult.getJob() != null
                    && config.getS3Verification().isEnabled()) {
                S3CheckResult s3Result = s3VerificationService.verify(
                    manager.id(), instance.id(),
                    instance.name(), jobResult.getJob());

                // S3-Ergebnis am Job merken für RestoreTestMonitor
                jobResult.setS3CheckResult(s3Result);
            }

            run.complete(...);
        }
    }
    retentionCleanup.cleanup();
}
```

---

## 9. Anbindung RestoreTestMonitor

Der RestoreTestMonitor bevorzugt einen S3-verifizierten Job:

```java
private RestoreTestResult runSingleRestoreTest(RestoreTask task) {

    // Letzten Job laden – bevorzugt einen S3-verifizierten
    BackupJob job = findBestJobForRestore(task.managerId(), task.instanceId());

    SandboxConnection sandbox = sandboxManager.getSandbox(task.instanceId());
    try {
        sandboxProvisioner.reset(sandbox);

        // Restore via Agent mit S3-Credentials aus dem Job
        String agentJobId = UUID.randomUUID().toString();
        agentClient.triggerRestore(
            task.managerId(), agentJobId,
            job.getDestination(),
            resolveFilename(job),
            sandbox);

        AgentRestoreStatus status = agentClient.pollStatus(...);
        if (status != AgentRestoreStatus.SUCCEEDED)
            return RestoreTestResult.restoreFailed(task.instanceId(), status);

        List<QueryCheckResult> checks =
            contentChecker.runChecks(sandbox, task.instanceId());

        return checks.stream().allMatch(QueryCheckResult::passed)
            ? RestoreTestResult.ok(task.instanceId(), checks)
            : RestoreTestResult.validationFailed(task.instanceId(), checks);

    } finally {
        sandboxProvisioner.reset(sandbox);
    }
}

private BackupJob findBestJobForRestore(String managerId, String instanceId) {
    // Zuerst: letzten Job suchen der S3-verifiziert ist
    return s3CheckResultRepo
        .findLatestPassedForInstance(instanceId)
        .flatMap(s3Check ->
            managerClient.getJobById(managerId, s3Check.getBackupJobId()))
        .orElseGet(() ->
            // Fallback: letzten SUCCEEDED-Job nehmen
            managerClient.getLatestBackupJob(managerId, instanceId)
                .orElseThrow(() -> new IllegalStateException(
                    "No backup job found for restore test")));
}
```

---

## 10. Liquibase Changelog 006

### 006-create-s3-check-result.yaml

```yaml
databaseChangeLog:
  - changeSet:
      id: 006-create-s3-check-result
      author: backup-monitor
      comment: "S3-Verifikationsergebnisse pro Backup-Job"
      changes:
        - createTable:
            tableName: s3_check_result
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_s3_check_result
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
                  name: backup_job_id
                  type: VARCHAR(256)
                  constraints:
                    nullable: false
              - column:
                  name: filename
                  type: VARCHAR(512)
              - column:
                  name: bucket
                  type: VARCHAR(256)
              - column:
                  name: exists
                  type: BOOLEAN
                  defaultValueBoolean: false
              - column:
                  name: size_expected_bytes
                  type: BIGINT
              - column:
                  name: size_actual_bytes
                  type: BIGINT
              - column:
                  name: size_match_within_tolerance
                  type: BOOLEAN
                  defaultValueBoolean: false
              - column:
                  name: size_deviation_percent
                  type: DECIMAL(6,2)
              - column:
                  name: accessible
                  type: BOOLEAN
                  defaultValueBoolean: false
              - column:
                  name: magic_bytes_valid
                  type: BOOLEAN
                  defaultValueBoolean: false
              - column:
                  name: all_passed
                  type: BOOLEAN
                  defaultValueBoolean: false
              - column:
                  name: error_message
                  type: TEXT
                  remarks: "Gesetzt bei unerwarteten Exceptions während der Prüfung"
              - column:
                  name: checked_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
                  defaultValueComputed: NOW()
        - createIndex:
            tableName: s3_check_result
            indexName: idx_s3_check_instance_time
            columns:
              - column:
                  name: instance_id
              - column:
                  name: checked_at
                  descending: true
        - createIndex:
            tableName: s3_check_result
            indexName: idx_s3_check_job_id
            columns:
              - column:
                  name: backup_job_id
        - createIndex:
            tableName: s3_check_result
            indexName: idx_s3_check_passed_instance
            columns:
              - column:
                  name: all_passed
              - column:
                  name: instance_id
              - column:
                  name: checked_at
                  descending: true
```

---

## 11. Aktualisierter db.changelog-master.yaml

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
  - include:
      file: db/changelog/changes/006-create-s3-check-result.yaml
```

---

## 12. Retention für S3-Check-Ergebnisse

```yaml
# application.yml – Ergänzung
backup-monitor:
  retention:
    job-check-entries:      ${RETENTION_JOB_CHECK:30}
    restore-test-entries:   ${RETENTION_RESTORE_TEST:10}
    s3-check-entries:       ${RETENTION_S3_CHECK:30}   # NEU
```

```java
// RetentionCleanupJob.java – Ergänzung
public void cleanup() {
    monitorRunRepo.deleteOldEntriesPerInstanceAndType(
        RunType.JOB_CHECK, config.getRetention().getJobCheckEntries());
    monitorRunRepo.deleteOldEntriesPerInstanceAndType(
        RunType.RESTORE_TEST, config.getRetention().getRestoreTestEntries());
    // NEU: S3-Check-Retention
    s3CheckResultRepo.deleteOldEntriesPerInstance(
        config.getRetention().getS3CheckEntries());
}
```

---

## 13. Dependencies (Ergänzung zu pom.xml)

```xml
<!-- AWS SDK v2 für S3 (MinIO-kompatibel via endpointOverride) -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.x</version>
    <!-- Version über BOM verwalten: -->
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>apache-client</artifactId>
    <version>2.x</version>
    <!-- Für insecure HTTP Client bei skipSSL=true -->
</dependency>

<!-- AWS BOM -->
<dependencyManagement>
  <dependencies>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>bom</artifactId>
        <version>2.31.x</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

---

## 14. Prometheus-Alertregeln (Ergänzung)

```yaml
# Ergänzung zu prometheus-rules.yml
- alert: BackupS3FileMissing
  expr: backup_s3_file_exists == 0
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Backup-Datei nicht in S3: {{ $labels.instance_name }}"
    description: "Backup-Job erfolgreich, aber Datei fehlt im S3-Bucket"

- alert: BackupS3NotAccessible
  expr: backup_s3_accessible == 0
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Backup-Datei in S3 nicht lesbar: {{ $labels.instance_name }}"

- alert: BackupS3SizeMismatch
  expr: backup_s3_size_match == 0
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Backup-Dateigröße weicht ab: {{ $labels.instance_name }}"
    description: "Abweichung: {{ $value }}%"

- alert: BackupS3CorruptMagicBytes
  expr: backup_s3_magic_bytes_valid == 0 and backup_s3_accessible == 1
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Backup-Datei ggf. kein gzip: {{ $labels.instance_name }}"
```

---

## 15. Aktualisierte Implementierungsreihenfolge

| Phase | Aufgaben | Ergebnis |
|-------|----------|---------|
| 1–11 | Wie v4 | Basis-Monitoring läuft |
| **12** | AWS SDK v2 Dependency, `S3ClientFactory` | S3-Verbindung aufbaubar |
| **13** | `S3CheckResult`, `S3CheckResultEntity`, `S3CheckResultRepository` | Datenmodell |
| **14** | Liquibase-Changelog 006, Master aktualisieren | Tabelle angelegt |
| **15** | `S3VerificationService` (exists, size, accessible, magic bytes) | S3-Checks laufen |
| **16** | `MetricsPublisher` S3-Metriken, `MetricNames` ergänzen | Metriken auf `/actuator/prometheus` |
| **17** | `MonitoringOrchestrator` integrieren (nach Job-Check) | Pipeline komplett |
| **18** | `RestoreTestMonitor.findBestJobForRestore()` | Restore nutzt S3-verifizierten Job |
| **19** | Retention für S3-Checks ergänzen | Cleanup vollständig |
| **20** | Integrationstests: S3 via Testcontainers MinIO | Testsuite grün |
| **21** | CF-Deployment, Prometheus-Regeln ergänzen | Live |
