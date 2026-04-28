# CloudFoundry Backup Monitor

> **[English](#english) | [Deutsch](#deutsch)**

---

## English

### Overview

The Backup Monitor is a Spring Boot 4 / Java 21 service that continuously verifies the health of PostgreSQL backups managed by an OSB (Open Service Broker) backup manager. It covers three independent verification layers:

1. **Job Check** – Verifies that the latest backup job for each configured service instance completed successfully. Tracks consecutive failures, detects overdue backups based on the plan's cron schedule, and flags plans that have never produced a successful job.
2. **S3 Verification** – Checks that the backup file exists in the configured S3-compatible object store, that the bucket is reachable, that the file is accessible, that the reported size matches the actual size exactly, that the file has valid archive magic bytes (gzip or uncompressed tar), and monitors file size and duration trends across runs. Also compares the number of stored backup files against the expected count derived from the plan's retention settings.
3. **Restore Test** – Triggers a full restore of a recent backup into a sandbox database and runs configurable SQL validation queries against the restored data.

All results are persisted to PostgreSQL and exposed as Prometheus metrics. Metrics are restored from the database on startup so no state is lost after a restart. Distributed locking via ShedLock prevents duplicate executions in multi-instance deployments.

---

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      MonitoringScheduler                     │
│          (Cron + ShedLock distributed locking)               │
└────────────┬─────────────────────────┬───────────────────────┘
             │                         │
             ▼                         ▼
   MonitoringOrchestrator        RestoreTestMonitor
   ┌────────────────────────┐    ┌──────────────────────────┐
   │ BackupPlanMonitor      │    │ SandboxManager           │
   │ BackupJobMonitor       │    │ SandboxProvisioner (CF)  │
   │ ConsecutiveFailuresSvc │    │ AgentClient (restore)    │
   │ S3VerificationService  │    │ DatabaseContentChecker   │
   │ MetricsPublisher       │    │ MetricsPublisher         │
   │ MonitorRunRepository   │    └──────────────────────────┘
   └────────────────────────┘
             │
             ▼
        PostgreSQL
   (monitor_run, s3_check_result,
    instance_job_state,
    restore_test_result, sandbox_registry,
    stored_token, shedlock)
```

---

### Prerequisites

| Dependency | Minimum version |
|---|---|
| Java | 21 |
| PostgreSQL | 14+ |
| OSB Backup Manager | [evoila/osb-backup-manager](https://github.com/evoila/osb-backup-manager) public API |
| OSB Backup Agent | [evoila/osb-backup-agent](https://github.com/evoila/osb-backup-agent) public API |
| S3-compatible store | AWS S3, MinIO, Ceph, etc. |
| CloudFoundry | V3 API (for restore test provisioning, optional) |

---

### Getting Started

```bash
# Build
mvn package -DskipTests

# Run with local profile
TOKEN_ENCRYPTION_KEY=mysecretkey \
  java -jar target/cf-backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# Run with CF profile (production)
java -jar target/cf-backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=cf
```

The database schema is applied automatically via Liquibase on startup.

---

### Configuration Reference

All properties are under the `cf-backup-monitor.*` namespace and can be overridden by environment variables.

#### Core / Encryption

| Property | Env variable | Default | Description |
|---|---|---|---|
| `cf-backup-monitor.encryption.key` | `TOKEN_ENCRYPTION_KEY` | *(required)* | AES-256-GCM key for encrypting UAA tokens and sandbox passwords at rest |

#### Scheduling

| Property | Env variable | Default (cron) | Description |
|---|---|---|---|
| `cf-backup-monitor.scheduling.job-check-cron` | `JOB_CHECK_CRON` | `0 0 6 * * *` (06:00 daily) | When to run backup job checks |
| `cf-backup-monitor.restore-test.cron` | `RESTORE_TEST_CRON` | `0 0 3 * * 0` (03:00 Sundays) | When to run restore tests |
| `cf-backup-monitor.scheduling.orphan-cleanup-cron` | `ORPHAN_CLEANUP_CRON` | `0 0 4 * * *` (04:00 daily) | When to clean up orphaned CF sandbox instances |

#### Managers (`cf-backup-monitor.managers[]`)

Each entry represents one OSB backup manager instance.

| Property | Env variable (example) | Description |
|---|---|---|
| `id` | – | Unique manager ID, used as metric label |
| `name` | – | Human-readable name |
| `url` | `MANAGER_1_URL` | Base URL of the backup manager REST API |
| `agent-url` | `MANAGER_1_AGENT_URL` | Base URL of the backup agent REST API |
| `agent-username` | `MANAGER_1_AGENT_USERNAME` | HTTP Basic username for the agent |
| `agent-password` | `MANAGER_1_AGENT_PASSWORD` | HTTP Basic password for the agent |
| `cf.uaa-endpoint` | `MANAGER_1_UAA_ENDPOINT` | UAA endpoint for CF token retrieval |
| `cf.cf-api-endpoint` | `MANAGER_1_CF_API_ENDPOINT` | CF V3 API base URL |
| `cf.service-account.username` | `MANAGER_1_SA_USERNAME` | CF service account username |
| `cf.service-account.password` | `MANAGER_1_SA_PASSWORD` | CF service account password |
| `instances[].id` | `MANAGER_1_INSTANCE_1_ID` | CF service instance GUID |
| `instances[].name` | `MANAGER_1_INSTANCE_1_NAME` | Human-readable instance name used as backup plan name |
| `instances[].s3-instance-name` | `MANAGER_1_INSTANCE_1_S3_NAME` | Name of the S3 service instance for this backup (overrides offering-label search) |
| `instances[].s3-service-plan` | `MANAGER_1_INSTANCE_1_S3_PLAN` | CF service plan for S3 (e.g. `5gb`); required only if the instance should be created automatically |
| `instances[].items` | – | List of DB schema/database names to include in the backup (e.g. `["mydb"]`). Required for auto-provisioning to succeed. |

#### Auto-Provisioning (`cf-backup-monitor.auto-provision`)

Automatically creates a backup plan when none exists for a configured service instance.

| Property | Env variable | Default | Description |
|---|---|---|---|
| `cf-backup-monitor.auto-provision.enabled` | `AUTO_PROVISION_ENABLED` | `false` | Enable auto-provisioning |
| `cf-backup-monitor.auto-provision.s3-service-label` | `AUTO_PROVISION_S3_LABEL` | `s3` | CF service offering name of the S3 store (e.g. `ecs-bucket`) |
| `cf-backup-monitor.auto-provision.backup-schedule` | `AUTO_PROVISION_SCHEDULE` | `0 2 * * *` | Cron schedule for the new backup plan (5-field) |
| `cf-backup-monitor.auto-provision.retention-style` | `AUTO_PROVISION_RETENTION_STYLE` | `FILES` | Retention strategy: `ALL`, `DAYS`, `FILES`, or `HOURS` |
| `cf-backup-monitor.auto-provision.retention-period` | `AUTO_PROVISION_RETENTION_PERIOD` | `7` | Number of backups/days/hours to retain (must be > 0) |
| `cf-backup-monitor.auto-provision.timezone` | `AUTO_PROVISION_TIMEZONE` | `UTC` | Timezone for the backup schedule (e.g. `Europe/Berlin`) |
| `cf-backup-monitor.auto-provision.plan-name` | `AUTO_PROVISION_PLAN_NAME` | `Auto-Backup` | Default backup plan name (overridden by `instances[].name`) |

Prerequisites: `cf.space-guid` must be set per manager. If `instances[].s3-instance-name` is set and the instance does not exist yet, it is created automatically when `instances[].s3-service-plan` is also configured.

#### S3 Verification

| Property | Env variable | Default | Description |
|---|---|---|---|
| `cf-backup-monitor.s3-verification.enabled` | `S3_VERIFY_ENABLED` | `true` | Enable/disable S3 file verification |
| `cf-backup-monitor.s3-verification.accessibility-check-bytes` | `S3_ACCESSIBILITY_BYTES` | `1024` | Number of bytes to download for the accessibility and magic-bytes check |
| `cf-backup-monitor.s3-verification.shrink-warning-threshold-percent` | `S3_SHRINK_THRESHOLD` | `20` | Warn if the backup file shrank by more than this percentage compared to the previous run |
| `cf-backup-monitor.s3-verification.growth-warning-threshold-percent` | `S3_GROWTH_THRESHOLD` | `50` | Warn if the backup file grew by more than this percentage compared to the previous run |
| `cf-backup-monitor.s3-verification.duration-growth-threshold-percent` | `S3_DURATION_GROWTH_THRESHOLD` | `50` | Warn if backup execution time grew by more than this percentage compared to the previous run |
| `cf-backup-monitor.s3-verification.overdue-tolerance-percent` | `S3_OVERDUE_TOLERANCE_PCT` | `25` | Grace period after the next scheduled backup fire before the job is considered overdue (as a percentage of the cron interval) |

Size and duration trend comparisons are only performed when the compression setting of the backup plan has not changed between runs, to avoid false positives after a configuration change.

The overdue check uses the backup plan's 6-field Quartz cron expression. It computes the next expected fire time after the last successful job's end date, then adds the tolerance as a grace period. If now is past that deadline and no newer job has run, `backup_job_overdue` is set to `1`. Returns `-1` if no job has run yet or if the cron cannot be parsed.

#### Restore Test

| Property | Env variable | Default | Description |
|---|---|---|---|
| `cf-backup-monitor.restore-test.enabled` | `RESTORE_TEST_ENABLED` | `true` | Enable/disable restore tests |
| `cf-backup-monitor.restore-test.max-parallel` | `RESTORE_TEST_MAX_PARALLEL` | `2` | Maximum number of simultaneous restore tests |
| `cf-backup-monitor.restore-test.timeout-minutes` | `RESTORE_TEST_TIMEOUT` | `45` | Timeout per restore test in minutes |

#### Sandbox Configuration (`cf-backup-monitor.restore-test.sandboxes[]`)

Each entry maps a service instance to its sandbox database.

| Property | Default | Description |
|---|---|---|
| `instance-id` | *(required)* | References a configured service instance |
| `mode` | `existing` | `existing` = use a fixed sandbox DB; `provision` = create a CF service instance |
| `existing.host` | – | Host of the pre-existing sandbox PostgreSQL |
| `existing.port` | `5432` | Port |
| `existing.database` | – | Database name |
| `existing.username` | – | Username |
| `existing.password` | – | Password |
| `provision.org` | – | CF org for provisioning |
| `provision.space` | – | CF space for provisioning |
| `provision.service` | `postgresql` | CF service offering name |
| `provision.plan` | `small` | CF service plan name |
| `provision.instance-name-prefix` | `restore-test-sandbox` | Prefix for the created CF service instance name |

#### Validation Queries (`cf-backup-monitor.restore-test.validations[]`)

| Property | Description |
|---|---|
| `instance-id` | References a configured service instance |
| `queries[].description` | Human-readable check description |
| `queries[].sql` | SQL query that must return a single numeric value in the first column |
| `queries[].min-result` | Minimum acceptable value (inclusive) |

#### Retention

| Property | Env variable | Default | Description |
|---|---|---|---|
| `cf-backup-monitor.retention.job-check-entries` | `RETENTION_JOB_CHECK` | `30` | How many `JOB_CHECK` runs to retain per instance |
| `cf-backup-monitor.retention.restore-test-entries` | `RETENTION_RESTORE_TEST` | `10` | How many `RESTORE_TEST` runs to retain per instance |
| `cf-backup-monitor.retention.s3-check-entries` | `RETENTION_S3_CHECK` | `30` | How many S3 check results to retain per instance |

#### Database

| Property | Env variable | Description |
|---|---|---|
| `spring.datasource.url` | `PERSISTENCE_DB_URL` | JDBC URL, e.g. `jdbc:postgresql://host:5432/backup_monitor` |
| `spring.datasource.username` | `PERSISTENCE_DB_USERNAME` | Database user |
| `spring.datasource.password` | `PERSISTENCE_DB_PASSWORD` | Database password |

---

### Exposed Endpoints

#### Spring Actuator

| Path | Description |
|---|---|
| `GET /actuator/health` | Application health including DB connectivity |
| `GET /actuator/prometheus` | All Prometheus metrics (see below) |
| `GET /actuator/info` | Application build info |

#### Prometheus Metrics

All metrics carry the labels `manager_id`, `instance_id`, and `instance_name` unless noted otherwise.

**Backup Plan**

| Metric | Type | Description |
|---|---|---|
| `backup_plan_active` | Gauge | `1` = plan exists and is not paused, `0` = missing or paused |
| `backup_plan_paused` | Gauge | `1` = plan is currently paused |
| `backup_plan_has_succeeded_job` | Gauge | `1` = the plan has produced at least one SUCCEEDED job ever, `0` = never succeeded |

**Backup Job**

| Metric | Type | Description |
|---|---|---|
| `backup_job_last_status` | Gauge | `1` = last job SUCCEEDED, `0` = otherwise |
| `backup_job_last_age_hours` | Gauge | Age of the last backup in hours; `-1` = no job found |
| `backup_job_last_filesize_bytes` | Gauge | Reported file size of the last backup in bytes |
| `backup_job_last_duration_ms` | Gauge | Total execution time of the last backup job in milliseconds |
| `backup_job_consecutive_failures` | Gauge | Number of consecutive failed checks since the last success; `0` = last check succeeded |
| `backup_job_overdue` | Gauge | `1` = last backup is overdue based on the plan's cron schedule and tolerance; `0` = on time; `-1` = not determinable |
| `backup_job_success_total` | Counter | Total number of successful job checks |
| `backup_job_failure_total` | Counter | Total number of failed job checks |

**S3 Verification**

| Metric | Type | Description |
|---|---|---|
| `backup_s3_bucket_accessible` | Gauge | `1` = S3 bucket responds to a metadata request |
| `backup_s3_file_exists` | Gauge | `1` = backup file found in S3 |
| `backup_s3_size_match` | Gauge | `1` = actual S3 file size matches the size reported by the backup agent exactly |
| `backup_s3_file_size_bytes` | Gauge | Actual file size in S3 in bytes |
| `backup_s3_size_shrink_warning` | Gauge | `1` = file is significantly smaller than the previous backup (threshold configurable) |
| `backup_s3_size_growth_warning` | Gauge | `1` = file is significantly larger than the previous backup (threshold configurable) |
| `backup_s3_accessible` | Gauge | `1` = file bytes downloadable via range request |
| `backup_s3_magic_bytes_valid` | Gauge | `1` = file starts with valid archive signature: gzip (`1F 8B`) or uncompressed tar (ustar at offset 257) |
| `backup_s3_duration_growth_warning` | Gauge | `1` = backup duration has grown significantly compared to the previous run |
| `backup_s3_file_count` | Gauge | Number of backup files currently stored under the plan's S3 prefix |
| `backup_s3_expected_file_count` | Gauge | Expected number of files based on the plan's retention settings (only emitted when determinable) |
| `backup_s3_all_checks_passed` | Gauge | `1` = exists, size match, accessible, and magic bytes all passed |

**Restore Test**

| Metric | Type | Description |
|---|---|---|
| `backup_restore_last_status` | Gauge | `1` = OK, `0` = FAILED, `-1` = SKIPPED, `-2` = NO_RESOURCES |
| `backup_restore_last_duration_seconds` | Gauge | Duration of the last restore test in seconds |
| `backup_restore_validation_passed` | Gauge | `1` = all SQL validation queries passed |

**General**

| Metric | Type | Labels | Description |
|---|---|---|---|
| `backup_monitor_last_run_timestamp` | Gauge | `manager_id` | Unix timestamp of the last completed monitoring run |

---

### Database Schema

The schema is managed by Liquibase and created automatically. The following tables are used:

| Table | Purpose |
|---|---|
| `monitor_run` | One row per job-check or restore-test execution; retention-pruned |
| `s3_check_result` | Detailed S3 verification results including size, trend warnings, file count, and magic bytes; retention-pruned |
| `instance_job_state` | Persistent per-instance state: consecutive failure counter and whether a successful job has ever been observed |
| `restore_test_result` | Detailed restore test outcomes including per-query results (JSONB) |
| `sandbox_registry` | Persistent registry of provisioned CF sandbox instances |
| `stored_token` | Encrypted UAA access tokens per manager |
| `shedlock` | Distributed lock table (ShedLock) |

---

### How the Restore Test Works

The monitor does not perform the actual restore itself. Responsibilities are split across three components:

| Component | Role |
|---|---|
| **Backup Manager** | Provides backup job metadata: S3 bucket, filename, credentials |
| **Backup Agent** | Downloads the backup from S3 and restores it into the sandbox database |
| **Monitor** | Orchestrates: prepare sandbox → trigger agent → poll status → validate |

#### Sandbox Modes

**`existing`** — A pre-configured PostgreSQL instance is used directly. Connection details (host, port, database, credentials) are fixed in the configuration. The monitor connects via JDBC and resets the schema before each restore (`DROP SCHEMA public CASCADE; CREATE SCHEMA public`).

**`provision`** — A new PostgreSQL service instance is created in CloudFoundry before the test, a service key is generated to retrieve credentials, and the instance is registered in the `sandbox_registry` table for reuse in subsequent runs. The schema is reset before each use. Only one CF instance can be provisioned at a time (serialized internally). Requires:
- CF V3 API access with a configured service account
- A PostgreSQL service offering available in the target CF space
- `cf.space-guid` set per manager
- Sufficient CF quota for a new service instance

#### Restore Flow

```
1. Find the most recent successful backup job (prefers jobs with passed S3 verification)
2. Prepare sandbox (existing: reuse; provision: create CF instance if not yet registered)
3. Reset sandbox schema (DROP SCHEMA public CASCADE)
4. Trigger restore via Agent REST API (S3 source + sandbox DB as target)
5. Poll agent status every 10 s until SUCCEEDED, FAILED, or timeout
6. Run configured SQL validation queries against the restored database
7. Persist result and publish metrics
8. Reset sandbox schema again (cleanup, even on failure)
```

#### Validation Queries

After a successful restore, each configured SQL query is executed against the sandbox. A query must return a single numeric value in the first column that is at or above `min-result`. Only `SELECT` statements are allowed. Results are stored as JSONB in `restore_test_result`.

#### Limitations

- Only **PostgreSQL** is supported as the restore target.
- CF provisioning is serialized: only one new sandbox instance is created at a time.
- SQL validation queries are limited to `SELECT` statements.
- There is no per-query timeout; a long-running query blocks the test until the overall restore timeout is reached.
- Orphaned CF sandbox instances from interrupted runs are cleaned up by a separate scheduler (`orphan-cleanup-cron`).

---

### Running Tests

```bash
# All tests (requires Docker for Testcontainers)
mvn test

# Skip integration tests
mvn test -Dgroups='!integration'
```

Tests use **Testcontainers** (PostgreSQL, MinIO) and **WireMock** – no external services required.

---

---

## Deutsch

### Überblick

Der Backup Monitor ist ein Spring Boot 4 / Java 21 Dienst, der kontinuierlich die Integrität von PostgreSQL-Backups prüft, die von einem OSB-Backup-Manager verwaltet werden. Er deckt drei unabhängige Verifikationsebenen ab:

1. **Job-Prüfung** – Verifiziert, ob der letzte Backup-Job jeder konfigurierten Service-Instanz erfolgreich abgeschlossen wurde. Zählt aufeinanderfolgende Fehler, erkennt überfällige Backups anhand des Cron-Zeitplans des Plans und meldet Pläne, die noch nie einen erfolgreichen Job erzeugt haben.
2. **S3-Verifikation** – Prüft, ob die Backup-Datei im konfigurierten S3-kompatiblen Object Store vorhanden und der Bucket erreichbar ist, ob die Datei abrufbar ist, ob die gemeldete Größe exakt mit der tatsächlichen übereinstimmt, ob die Datei mit einer gültigen Archiv-Signatur beginnt (gzip oder unkomprimiertes tar) und überwacht Größen- und Laufzeitentwicklungen über mehrere Läufe hinweg. Zusätzlich wird die Anzahl gespeicherter Backup-Dateien mit dem erwarteten Wert aus den Retention-Einstellungen verglichen.
3. **Restore-Test** – Löst eine vollständige Wiederherstellung eines aktuellen Backups in eine Sandbox-Datenbank aus und führt konfigurierbare SQL-Validierungsabfragen gegen die wiederhergestellten Daten durch.

Alle Ergebnisse werden in PostgreSQL gespeichert und als Prometheus-Metriken bereitgestellt. Beim Start werden die Metriken aus der Datenbank wiederhergestellt, sodass nach einem Neustart kein Zustand verloren geht. Verteiltes Sperren via ShedLock verhindert Doppelausführungen in Multi-Instanz-Deployments.

---

### Architektur

```
┌──────────────────────────────────────────────────────────────┐
│                      MonitoringScheduler                     │
│            (Cron + ShedLock verteiltes Sperren)              │
└────────────┬─────────────────────────┬───────────────────────┘
             │                         │
             ▼                         ▼
   MonitoringOrchestrator        RestoreTestMonitor
   ┌────────────────────────┐    ┌──────────────────────────┐
   │ BackupPlanMonitor      │    │ SandboxManager           │
   │ BackupJobMonitor       │    │ SandboxProvisioner (CF)  │
   │ ConsecutiveFailuresSvc │    │ AgentClient (Restore)    │
   │ S3VerificationService  │    │ DatabaseContentChecker   │
   │ MetricsPublisher       │    │ MetricsPublisher         │
   │ MonitorRunRepository   │    └──────────────────────────┘
   └────────────────────────┘
             │
             ▼
        PostgreSQL
   (monitor_run, s3_check_result,
    instance_job_state,
    restore_test_result, sandbox_registry,
    stored_token, shedlock)
```

---

### Voraussetzungen

| Abhängigkeit | Mindestversion |
|---|---|
| Java | 21 |
| PostgreSQL | 14+ |
| OSB-Backup-Manager | [evoila/osb-backup-manager](https://github.com/evoila/osb-backup-manager) öffentliche API |
| OSB-Backup-Agent | [evoila/osb-backup-agent](https://github.com/evoila/osb-backup-agent) öffentliche API |
| S3-kompatibler Store | AWS S3, MinIO, Ceph usw. |
| CloudFoundry | V3-API (nur für Restore-Test-Provisionierung, optional) |

---

### Schnellstart

```bash
# Build
mvn package -DskipTests

# Mit lokalem Profil starten
TOKEN_ENCRYPTION_KEY=meingeheimerschluessel \
  java -jar target/cf-backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# Mit CF-Profil (Produktion)
java -jar target/cf-backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=cf
```

Das Datenbankschema wird beim Start automatisch über Liquibase angewendet.

---

### Konfigurationsreferenz

Alle Eigenschaften befinden sich unter dem Namespace `cf-backup-monitor.*` und können durch Umgebungsvariablen überschrieben werden.

#### Kern / Verschlüsselung

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.encryption.key` | `TOKEN_ENCRYPTION_KEY` | *(erforderlich)* | AES-256-GCM-Schlüssel zur verschlüsselten Ablage von UAA-Tokens und Sandbox-Passwörtern |

#### Zeitplanung

| Eigenschaft | Umgebungsvariable | Standard (Cron) | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.scheduling.job-check-cron` | `JOB_CHECK_CRON` | `0 0 6 * * *` (täglich 06:00) | Zeitplan für Job-Prüfungen |
| `cf-backup-monitor.restore-test.cron` | `RESTORE_TEST_CRON` | `0 0 3 * * 0` (sonntags 03:00) | Zeitplan für Restore-Tests |
| `cf-backup-monitor.scheduling.orphan-cleanup-cron` | `ORPHAN_CLEANUP_CRON` | `0 0 4 * * *` (täglich 04:00) | Zeitplan für die Bereinigung verwaister CF-Sandbox-Instanzen |

#### Manager (`cf-backup-monitor.managers[]`)

Jeder Eintrag repräsentiert eine OSB-Backup-Manager-Instanz.

| Eigenschaft | Umgebungsvariable (Beispiel) | Beschreibung |
|---|---|---|
| `id` | – | Eindeutige Manager-ID, wird als Metrik-Label verwendet |
| `name` | – | Menschenlesbarer Name |
| `url` | `MANAGER_1_URL` | Basis-URL der Backup-Manager-REST-API |
| `agent-url` | `MANAGER_1_AGENT_URL` | Basis-URL der Backup-Agent-REST-API |
| `agent-username` | `MANAGER_1_AGENT_USERNAME` | HTTP-Basic-Benutzername für den Agent |
| `agent-password` | `MANAGER_1_AGENT_PASSWORD` | HTTP-Basic-Passwort für den Agent |
| `cf.uaa-endpoint` | `MANAGER_1_UAA_ENDPOINT` | UAA-Endpoint für CF-Token-Abruf |
| `cf.cf-api-endpoint` | `MANAGER_1_CF_API_ENDPOINT` | CF V3 API Basis-URL |
| `cf.service-account.username` | `MANAGER_1_SA_USERNAME` | CF-Service-Account-Benutzername |
| `cf.service-account.password` | `MANAGER_1_SA_PASSWORD` | CF-Service-Account-Passwort |
| `instances[].id` | `MANAGER_1_INSTANCE_1_ID` | CF-Service-Instanz-GUID |
| `instances[].name` | `MANAGER_1_INSTANCE_1_NAME` | Menschenlesbarer Instanzname, wird als Backup-Plan-Name verwendet |
| `instances[].s3-instance-name` | `MANAGER_1_INSTANCE_1_S3_NAME` | Name der S3-Service-Instanz für das Auto-Provisioning (optional) |
| `instances[].s3-service-plan` | `MANAGER_1_INSTANCE_1_S3_PLAN` | CF-Service-Plan für das automatische Anlegen der S3-Instanz (optional) |
| `instances[].items` | – | Liste der zu sichernden DB-Schemas/Datenbanknamen (z.B. `["meindb"]`). Pflichtfeld für erfolgreiches Auto-Provisioning. |

#### Auto-Provisioning

Wenn kein Backup-Plan für eine Instanz existiert, kann die Anwendung automatisch einen anlegen.

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.auto-provision.enabled` | `AUTO_PROVISION_ENABLED` | `false` | Auto-Provisioning aktivieren |
| `cf-backup-monitor.auto-provision.s3-service-label` | `AUTO_PROVISION_S3_LABEL` | `s3` | CF-Service-Angebots-Label für die S3-Suche im Space |
| `cf-backup-monitor.auto-provision.backup-schedule` | `AUTO_PROVISION_SCHEDULE` | `0 2 * * *` | Cron-Ausdruck für den neuen Backup-Plan (5-stellig) |
| `cf-backup-monitor.auto-provision.retention-style` | `AUTO_PROVISION_RETENTION_STYLE` | `FILES` | Aufbewahrungsstrategie: `ALL`, `DAYS`, `FILES` oder `HOURS` |
| `cf-backup-monitor.auto-provision.retention-period` | `AUTO_PROVISION_RETENTION_PERIOD` | `7` | Anzahl aufzubewahrender Einheiten (muss > 0 sein) |
| `cf-backup-monitor.auto-provision.timezone` | `AUTO_PROVISION_TIMEZONE` | `UTC` | Zeitzone für den Backup-Schedule (z.B. `Europe/Berlin`) |
| `cf-backup-monitor.auto-provision.plan-name` | `AUTO_PROVISION_PLAN_NAME` | `Auto-Backup` | Standard-Backup-Plan-Name (wird durch `instances[].name` übersteuert) |

Voraussetzungen: `auto-provision.enabled=true` und `cf.space-guid` je Manager müssen gesetzt sein.
Ist `instances[].s3-instance-name` gesetzt, wird genau diese S3-Instanz gesucht (bzw. bei konfiguriertem `s3-service-plan` automatisch angelegt).

#### S3-Verifikation

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.s3-verification.enabled` | `S3_VERIFY_ENABLED` | `true` | S3-Dateiverifikation aktivieren/deaktivieren |
| `cf-backup-monitor.s3-verification.accessibility-check-bytes` | `S3_ACCESSIBILITY_BYTES` | `1024` | Anzahl herunterzuladender Bytes für Zugriffstest und Magic-Bytes-Prüfung |
| `cf-backup-monitor.s3-verification.shrink-warning-threshold-percent` | `S3_SHRINK_THRESHOLD` | `20` | Warnung, wenn die Backup-Datei um mehr als diesen Prozentwert gegenüber dem Vorgänger geschrumpft ist |
| `cf-backup-monitor.s3-verification.growth-warning-threshold-percent` | `S3_GROWTH_THRESHOLD` | `50` | Warnung, wenn die Backup-Datei um mehr als diesen Prozentwert gegenüber dem Vorgänger gewachsen ist |
| `cf-backup-monitor.s3-verification.duration-growth-threshold-percent` | `S3_DURATION_GROWTH_THRESHOLD` | `50` | Warnung, wenn die Ausführungszeit um mehr als diesen Prozentwert gegenüber dem Vorgänger gestiegen ist |
| `cf-backup-monitor.s3-verification.overdue-tolerance-percent` | `S3_OVERDUE_TOLERANCE_PCT` | `25` | Kulanzzeit nach dem nächsten geplanten Backup-Feuerzeitpunkt, bevor ein Job als überfällig gilt (als Prozentsatz des Cron-Intervalls) |

Größen- und Laufzeitvergleiche werden nur durchgeführt, wenn sich die Kompressionseinstellung des Backup-Plans zwischen den Läufen nicht verändert hat, um Fehlalarme nach Konfigurationsänderungen zu vermeiden.

Die Überfälligkeitsprüfung verwendet den 6-stelligen Quartz-Cron-Ausdruck des Backup-Plans. Sie berechnet den nächsten erwarteten Feuerzeitpunkt nach dem Enddatum des letzten erfolgreichen Jobs und addiert die Toleranz als Kulanzzeit. Liegt der aktuelle Zeitpunkt nach dieser Deadline und kein neuerer Job wurde ausgeführt, wird `backup_job_overdue` auf `1` gesetzt. Gibt `-1` zurück, wenn noch kein Job gelaufen ist oder der Cron-Ausdruck nicht geparst werden kann.

#### Restore-Test

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.restore-test.enabled` | `RESTORE_TEST_ENABLED` | `true` | Restore-Tests aktivieren/deaktivieren |
| `cf-backup-monitor.restore-test.max-parallel` | `RESTORE_TEST_MAX_PARALLEL` | `2` | Maximale Anzahl gleichzeitiger Restore-Tests |
| `cf-backup-monitor.restore-test.timeout-minutes` | `RESTORE_TEST_TIMEOUT` | `45` | Timeout pro Restore-Test in Minuten |

#### Sandbox-Konfiguration (`cf-backup-monitor.restore-test.sandboxes[]`)

Jeder Eintrag ordnet einer Service-Instanz eine Sandbox-Datenbank zu.

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `instance-id` | *(erforderlich)* | Referenziert eine konfigurierte Service-Instanz |
| `mode` | `existing` | `existing` = vorhandene Sandbox-DB nutzen; `provision` = CF-Service-Instanz erstellen |
| `existing.host` | – | Host der vorhandenen Sandbox-PostgreSQL |
| `existing.port` | `5432` | Port |
| `existing.database` | – | Datenbankname |
| `existing.username` | – | Benutzername |
| `existing.password` | – | Passwort |
| `provision.org` | – | CF-Org für die Provisionierung |
| `provision.space` | – | CF-Space für die Provisionierung |
| `provision.service` | `postgresql` | Name des CF-Service-Angebots |
| `provision.plan` | `small` | Name des CF-Service-Plans |
| `provision.instance-name-prefix` | `restore-test-sandbox` | Präfix für den Namen der erstellten CF-Service-Instanz |

#### Validierungsabfragen (`cf-backup-monitor.restore-test.validations[]`)

| Eigenschaft | Beschreibung |
|---|---|
| `instance-id` | Referenziert eine konfigurierte Service-Instanz |
| `queries[].description` | Menschenlesbare Beschreibung der Prüfung |
| `queries[].sql` | SQL-Abfrage, die einen einzelnen numerischen Wert in der ersten Spalte zurückgeben muss |
| `queries[].min-result` | Mindestakzeptierter Wert (inklusive) |

#### Aufbewahrung (Retention)

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `cf-backup-monitor.retention.job-check-entries` | `RETENTION_JOB_CHECK` | `30` | Anzahl beizubehaltender `JOB_CHECK`-Läufe pro Instanz |
| `cf-backup-monitor.retention.restore-test-entries` | `RETENTION_RESTORE_TEST` | `10` | Anzahl beizubehaltender `RESTORE_TEST`-Läufe pro Instanz |
| `cf-backup-monitor.retention.s3-check-entries` | `RETENTION_S3_CHECK` | `30` | Anzahl beizubehaltender S3-Prüfergebnisse pro Instanz |

#### Datenbank

| Eigenschaft | Umgebungsvariable | Beschreibung |
|---|---|---|
| `spring.datasource.url` | `PERSISTENCE_DB_URL` | JDBC-URL, z. B. `jdbc:postgresql://host:5432/backup_monitor` |
| `spring.datasource.username` | `PERSISTENCE_DB_USERNAME` | Datenbankbenutzer |
| `spring.datasource.password` | `PERSISTENCE_DB_PASSWORD` | Datenbankpasswort |

---

### Bereitgestellte Endpunkte

#### Spring Actuator

| Pfad | Beschreibung |
|---|---|
| `GET /actuator/health` | Anwendungsgesundheit inkl. DB-Konnektivität |
| `GET /actuator/prometheus` | Alle Prometheus-Metriken (siehe unten) |
| `GET /actuator/info` | Build-Informationen der Anwendung |

#### Prometheus-Metriken

Alle Metriken tragen die Labels `manager_id`, `instance_id` und `instance_name`, sofern nicht anders angegeben.

**Backup-Plan**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_plan_active` | Gauge | `1` = Plan vorhanden und nicht pausiert, `0` = fehlt oder pausiert |
| `backup_plan_paused` | Gauge | `1` = Plan ist aktuell pausiert |
| `backup_plan_has_succeeded_job` | Gauge | `1` = Plan hatte mindestens einmal einen SUCCEEDED-Job, `0` = noch nie erfolgreich |

**Backup-Job**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_job_last_status` | Gauge | `1` = letzter Job SUCCEEDED, `0` = anderweitig |
| `backup_job_last_age_hours` | Gauge | Alter des letzten Backups in Stunden; `-1` = kein Job gefunden |
| `backup_job_last_filesize_bytes` | Gauge | Gemeldete Dateigröße des letzten Backups in Bytes |
| `backup_job_last_duration_ms` | Gauge | Gesamte Ausführungszeit des letzten Backup-Jobs in Millisekunden |
| `backup_job_consecutive_failures` | Gauge | Anzahl aufeinanderfolgender Fehler seit dem letzten Erfolg; `0` = zuletzt erfolgreich |
| `backup_job_overdue` | Gauge | `1` = Backup überfällig gemäß Cron-Plan und Toleranz; `0` = rechtzeitig; `-1` = nicht bestimmbar |
| `backup_job_success_total` | Counter | Gesamtanzahl erfolgreicher Job-Prüfungen |
| `backup_job_failure_total` | Counter | Gesamtanzahl fehlgeschlagener Job-Prüfungen |

**S3-Verifikation**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_s3_bucket_accessible` | Gauge | `1` = S3-Bucket antwortet auf eine Metadatenanfrage |
| `backup_s3_file_exists` | Gauge | `1` = Backup-Datei in S3 gefunden |
| `backup_s3_size_match` | Gauge | `1` = tatsächliche S3-Dateigröße stimmt exakt mit der vom Backup-Agent gemeldeten überein |
| `backup_s3_file_size_bytes` | Gauge | Tatsächliche Dateigröße in S3 in Bytes |
| `backup_s3_size_shrink_warning` | Gauge | `1` = Datei ist signifikant kleiner als beim vorherigen Lauf (Schwellwert konfigurierbar) |
| `backup_s3_size_growth_warning` | Gauge | `1` = Datei ist signifikant größer als beim vorherigen Lauf (Schwellwert konfigurierbar) |
| `backup_s3_accessible` | Gauge | `1` = Datei-Bytes per Range-Request abrufbar |
| `backup_s3_magic_bytes_valid` | Gauge | `1` = Datei beginnt mit einer gültigen Archiv-Signatur: gzip (`1F 8B`) oder unkomprimiertes tar (ustar an Offset 257) |
| `backup_s3_duration_growth_warning` | Gauge | `1` = Backup-Laufzeit ist gegenüber dem vorherigen Lauf signifikant gestiegen |
| `backup_s3_file_count` | Gauge | Anzahl aktuell gespeicherter Backup-Dateien unter dem S3-Präfix des Plans |
| `backup_s3_expected_file_count` | Gauge | Erwartete Dateianzahl laut Retention-Einstellungen des Plans (wird nur ausgegeben, wenn bestimmbar) |
| `backup_s3_all_checks_passed` | Gauge | `1` = Existenz, Größenübereinstimmung, Zugänglichkeit und Magic Bytes bestanden |

**Restore-Test**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_restore_last_status` | Gauge | `1` = OK, `0` = FAILED, `-1` = SKIPPED, `-2` = NO_RESOURCES |
| `backup_restore_last_duration_seconds` | Gauge | Dauer des letzten Restore-Tests in Sekunden |
| `backup_restore_validation_passed` | Gauge | `1` = alle SQL-Validierungsabfragen bestanden |

**Allgemein**

| Metrik | Typ | Labels | Beschreibung |
|---|---|---|---|
| `backup_monitor_last_run_timestamp` | Gauge | `manager_id` | Unix-Zeitstempel des letzten abgeschlossenen Monitoring-Laufs |

---

### Datenbankschema

Das Schema wird von Liquibase verwaltet und automatisch angelegt. Folgende Tabellen werden verwendet:

| Tabelle | Zweck |
|---|---|
| `monitor_run` | Je eine Zeile pro Job-Prüfungs- oder Restore-Test-Ausführung; durch Retention bereinigt |
| `s3_check_result` | Detaillierte S3-Verifikationsergebnisse inkl. Größe, Trend-Warnungen, Dateianzahl und Magic Bytes; durch Retention bereinigt |
| `instance_job_state` | Persistenter Zustand je Instanz: Zähler aufeinanderfolgender Fehler und ob je ein erfolgreicher Job beobachtet wurde |
| `restore_test_result` | Detaillierte Restore-Test-Ergebnisse inkl. Abfrageergebnissen pro Query (JSONB) |
| `sandbox_registry` | Persistente Registry provisionierter CF-Sandbox-Instanzen |
| `stored_token` | Verschlüsselte UAA-Zugriffstoken pro Manager |
| `shedlock` | Verteilte Sperrtabelle (ShedLock) |

---

### So funktioniert der Restore-Test

Der Monitor führt die eigentliche Rücksicherung nicht selbst durch. Die Verantwortung ist auf drei Komponenten verteilt:

| Komponente | Rolle |
|---|---|
| **Backup-Manager** | Liefert Backup-Job-Metadaten: S3-Bucket, Dateiname, Credentials |
| **Backup-Agent** | Lädt das Backup von S3 herunter und stellt es in der Sandbox-Datenbank wieder her |
| **Monitor** | Orchestriert: Sandbox bereitstellen → Agent triggern → Status pollen → Validieren |

#### Sandbox-Modi

**`existing`** — Eine vorkonfigurierte PostgreSQL-Instanz wird direkt verwendet. Die Verbindungsdaten (Host, Port, Datenbank, Credentials) sind fest in der Konfiguration hinterlegt. Der Monitor verbindet sich per JDBC und setzt das Schema vor jeder Rücksicherung zurück (`DROP SCHEMA public CASCADE; CREATE SCHEMA public`).

**`provision`** — Vor dem Test wird eine neue PostgreSQL-Service-Instanz in CloudFoundry angelegt, ein Service-Key zur Credential-Abfrage erstellt und die Instanz in der Tabelle `sandbox_registry` registriert, damit sie bei Folgeläufen wiederverwendet werden kann. Das Schema wird vor jeder Nutzung zurückgesetzt. Es kann immer nur eine CF-Instanz gleichzeitig provisioniert werden (intern serialisiert). Voraussetzungen:
- CF V3 API erreichbar mit konfiguriertem Service-Account
- PostgreSQL-Service-Angebot im Ziel-CF-Space vorhanden
- `cf.space-guid` je Manager konfiguriert
- Ausreichende CF-Quota für eine neue Service-Instanz

#### Ablauf der Rücksicherung

```
1. Neuesten erfolgreichen Backup-Job ermitteln
   (bevorzugt Jobs mit bestandener S3-Verifikation)
2. Sandbox bereitstellen (existing: wiederverwenden;
   provision: CF-Instanz anlegen, falls noch nicht registriert)
3. Sandbox-Schema zurücksetzen (DROP SCHEMA public CASCADE)
4. Rücksicherung per Agent REST-API triggern
   (S3 als Quelle, Sandbox-DB als Ziel)
5. Agent-Status alle 10 s pollen bis SUCCEEDED, FAILED oder Timeout
6. Konfigurierte SQL-Validierungsabfragen gegen die Sandbox ausführen
7. Ergebnis persistieren und Metriken publizieren
8. Sandbox-Schema erneut zurücksetzen (Cleanup, auch bei Fehler)
```

#### Validierungsabfragen

Nach erfolgreicher Rücksicherung wird jede konfigurierte SQL-Abfrage gegen die Sandbox ausgeführt. Eine Abfrage muss in der ersten Spalte einen einzelnen numerischen Wert zurückgeben, der mindestens `min-result` beträgt. Nur `SELECT`-Anweisungen sind erlaubt. Die Ergebnisse werden als JSONB in `restore_test_result` gespeichert.

#### Einschränkungen

- Nur **PostgreSQL** wird als Rücksicherungsziel unterstützt.
- CF-Provisionierung ist serialisiert: Es wird immer nur eine neue Sandbox-Instanz gleichzeitig erstellt.
- SQL-Validierungsabfragen sind auf `SELECT`-Anweisungen beschränkt.
- Es gibt keinen Query-spezifischen Timeout; eine lang laufende Abfrage blockiert den Test bis zum allgemeinen Restore-Timeout.
- Verwaiste CF-Sandbox-Instanzen aus unterbrochenen Läufen werden durch einen separaten Scheduler bereinigt (`orphan-cleanup-cron`).

---

### Tests ausführen

```bash
# Alle Tests (Docker für Testcontainers erforderlich)
mvn test

# Integrationstests überspringen
mvn test -Dgroups='!integration'
```

Tests verwenden **Testcontainers** (PostgreSQL, MinIO) und **WireMock** – keine externen Dienste erforderlich.
