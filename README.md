# Backup Monitor

> **[English](#english) | [Deutsch](#deutsch)**

---

## English

### Overview

The Backup Monitor is a Spring Boot 4 / Java 21 service that continuously verifies the health of PostgreSQL backups managed by an OSB (Open Service Broker) backup manager. It covers three independent verification layers:

1. **Job Check** – Verifies that the latest backup job for each configured service instance completed successfully.
2. **S3 Verification** – Checks that the backup file exists in the configured S3-compatible object store, is accessible, has a plausible file size, and starts with valid gzip magic bytes.
3. **Restore Test** – Triggers a full restore of a recent backup into a sandbox database and runs configurable SQL validation queries against the restored data.

All results are persisted to PostgreSQL and exposed as Prometheus metrics. Distributed locking via ShedLock prevents duplicate executions in multi-instance deployments.

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
   ┌──────────────────┐          ┌──────────────────────────┐
   │ BackupPlanMonitor│          │ SandboxManager           │
   │ BackupJobMonitor │          │ SandboxProvisioner (CF)  │
   │ S3Verification   │          │ AgentClient (restore)    │
   │ MetricsPublisher │          │ DatabaseContentChecker   │
   │ MonitorRunRepo   │          │ MetricsPublisher         │
   └──────────────────┘          └──────────────────────────┘
             │                         │
             └──────────┬──────────────┘
                        ▼
                   PostgreSQL
              (monitor_run, s3_check_result,
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
  java -jar target/backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# Run with CF profile (production)
java -jar target/backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=cf
```

The database schema is applied automatically via Liquibase on startup.

---

### Configuration Reference

All properties are under the `backup-monitor.*` namespace and can be overridden by environment variables.

#### Core / Encryption

| Property | Env variable | Default | Description |
|---|---|---|---|
| `backup-monitor.encryption.key` | `TOKEN_ENCRYPTION_KEY` | *(required)* | AES-256-GCM key for encrypting UAA tokens and sandbox passwords at rest |

#### Scheduling

| Property | Env variable | Default (cron) | Description |
|---|---|---|---|
| `backup-monitor.scheduling.job-check-cron` | `JOB_CHECK_CRON` | `0 0 6 * * *` (06:00 daily) | When to run backup job checks |
| `backup-monitor.restore-test.cron` | `RESTORE_TEST_CRON` | `0 0 3 * * 0` (03:00 Sundays) | When to run restore tests |
| `backup-monitor.scheduling.orphan-cleanup-cron` | `ORPHAN_CLEANUP_CRON` | `0 0 4 * * *` (04:00 daily) | When to clean up orphaned CF sandbox instances |

#### Managers (`backup-monitor.managers[]`)

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
| `instances[].name` | `MANAGER_1_INSTANCE_1_NAME` | Human-readable instance name |

#### S3 Verification

| Property | Env variable | Default | Description |
|---|---|---|---|
| `backup-monitor.s3-verification.enabled` | `S3_VERIFY_ENABLED` | `true` | Enable/disable S3 file verification |
| `backup-monitor.s3-verification.size-tolerance-percent` | `S3_SIZE_TOLERANCE` | `5` | Allowed deviation (%) between reported and actual S3 file size |
| `backup-monitor.s3-verification.accessibility-check-bytes` | `S3_ACCESSIBILITY_BYTES` | `1024` | Number of bytes to download for the accessibility check |

#### Restore Test

| Property | Env variable | Default | Description |
|---|---|---|---|
| `backup-monitor.restore-test.enabled` | `RESTORE_TEST_ENABLED` | `true` | Enable/disable restore tests |
| `backup-monitor.restore-test.max-parallel` | `RESTORE_TEST_MAX_PARALLEL` | `2` | Maximum number of simultaneous restore tests |
| `backup-monitor.restore-test.timeout-minutes` | `RESTORE_TEST_TIMEOUT` | `45` | Timeout per restore test in minutes |

#### Sandbox Configuration (`backup-monitor.restore-test.sandboxes[]`)

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

#### Validation Queries (`backup-monitor.restore-test.validations[]`)

| Property | Description |
|---|---|
| `instance-id` | References a configured service instance |
| `queries[].description` | Human-readable check description |
| `queries[].sql` | SQL query that must return a single numeric value in the first column |
| `queries[].min-result` | Minimum acceptable value (inclusive) |

#### Retention

| Property | Env variable | Default | Description |
|---|---|---|---|
| `backup-monitor.retention.job-check-entries` | `RETENTION_JOB_CHECK` | `30` | How many `JOB_CHECK` runs to retain per instance |
| `backup-monitor.retention.restore-test-entries` | `RETENTION_RESTORE_TEST` | `10` | How many `RESTORE_TEST` runs to retain per instance |
| `backup-monitor.retention.s3-check-entries` | `RETENTION_S3_CHECK` | `30` | How many S3 check results to retain per instance |

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

**Backup Job**

| Metric | Type | Description |
|---|---|---|
| `backup_job_last_status` | Gauge | `1` = last job SUCCEEDED, `0` = otherwise |
| `backup_job_last_age_hours` | Gauge | Age of the last backup in hours; `-1` = no job found |
| `backup_job_last_filesize_bytes` | Gauge | Reported file size of the last backup in bytes |
| `backup_job_success_total` | Counter | Total number of successful job checks |
| `backup_job_failure_total` | Counter | Total number of failed job checks |

**S3 Verification**

| Metric | Type | Description |
|---|---|---|
| `backup_s3_file_exists` | Gauge | `1` = backup file found in S3 |
| `backup_s3_size_match` | Gauge | `1` = file size within configured tolerance |
| `backup_s3_accessible` | Gauge | `1` = file bytes downloadable via range request |
| `backup_s3_magic_bytes_valid` | Gauge | `1` = file starts with valid gzip magic bytes (`1F 8B`) |
| `backup_s3_size_deviation_percent` | Gauge | Deviation between reported and actual file size in percent |
| `backup_s3_all_checks_passed` | Gauge | `1` = all four S3 checks passed |
| `backup_s3_file_size_bytes` | Gauge | Actual file size in S3 in bytes |

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
| `s3_check_result` | Detailed S3 verification results; retention-pruned |
| `restore_test_result` | Detailed restore test outcomes including per-query results (JSONB) |
| `sandbox_registry` | Persistent registry of provisioned CF sandbox instances |
| `stored_token` | Encrypted UAA access tokens per manager |
| `shedlock` | Distributed lock table (ShedLock) |

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

1. **Job-Prüfung** – Verifiziert, ob der letzte Backup-Job jeder konfigurierten Service-Instanz erfolgreich abgeschlossen wurde.
2. **S3-Verifikation** – Prüft, ob die Backup-Datei im konfigurierten S3-kompatiblen Object Store vorhanden ist, abrufbar ist, eine plausible Dateigröße aufweist und mit gültigen gzip Magic Bytes beginnt.
3. **Restore-Test** – Löst eine vollständige Wiederherstellung eines aktuellen Backups in eine Sandbox-Datenbank aus und führt konfigurierbare SQL-Validierungsabfragen gegen die wiederhergestellten Daten durch.

Alle Ergebnisse werden in PostgreSQL gespeichert und als Prometheus-Metriken bereitgestellt. Verteiltes Sperren via ShedLock verhindert Doppelausführungen in Multi-Instanz-Deployments.

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
   ┌──────────────────┐          ┌──────────────────────────┐
   │ BackupPlanMonitor│          │ SandboxManager           │
   │ BackupJobMonitor │          │ SandboxProvisioner (CF)  │
   │ S3-Verifikation  │          │ AgentClient (Restore)    │
   │ MetricsPublisher │          │ DatabaseContentChecker   │
   │ MonitorRunRepo   │          │ MetricsPublisher         │
   └──────────────────┘          └──────────────────────────┘
             │                         │
             └──────────┬──────────────┘
                        ▼
                   PostgreSQL
              (monitor_run, s3_check_result,
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
  java -jar target/backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# Mit CF-Profil (Produktion)
java -jar target/backup-monitor-0.1.0-SNAPSHOT.jar --spring.profiles.active=cf
```

Das Datenbankschema wird beim Start automatisch über Liquibase angewendet.

---

### Konfigurationsreferenz

Alle Eigenschaften befinden sich unter dem Namespace `backup-monitor.*` und können durch Umgebungsvariablen überschrieben werden.

#### Kern / Verschlüsselung

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `backup-monitor.encryption.key` | `TOKEN_ENCRYPTION_KEY` | *(erforderlich)* | AES-256-GCM-Schlüssel zur verschlüsselten Ablage von UAA-Tokens und Sandbox-Passwörtern |

#### Zeitplanung

| Eigenschaft | Umgebungsvariable | Standard (Cron) | Beschreibung |
|---|---|---|---|
| `backup-monitor.scheduling.job-check-cron` | `JOB_CHECK_CRON` | `0 0 6 * * *` (täglich 06:00) | Zeitplan für Job-Prüfungen |
| `backup-monitor.restore-test.cron` | `RESTORE_TEST_CRON` | `0 0 3 * * 0` (sonntags 03:00) | Zeitplan für Restore-Tests |
| `backup-monitor.scheduling.orphan-cleanup-cron` | `ORPHAN_CLEANUP_CRON` | `0 0 4 * * *` (täglich 04:00) | Zeitplan für die Bereinigung verwaister CF-Sandbox-Instanzen |

#### Manager (`backup-monitor.managers[]`)

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
| `instances[].name` | `MANAGER_1_INSTANCE_1_NAME` | Menschenlesbarer Instanzname |

#### S3-Verifikation

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `backup-monitor.s3-verification.enabled` | `S3_VERIFY_ENABLED` | `true` | S3-Dateiverifikation aktivieren/deaktivieren |
| `backup-monitor.s3-verification.size-tolerance-percent` | `S3_SIZE_TOLERANCE` | `5` | Erlaubte Abweichung (%) zwischen gemeldeter und tatsächlicher S3-Dateigröße |
| `backup-monitor.s3-verification.accessibility-check-bytes` | `S3_ACCESSIBILITY_BYTES` | `1024` | Anzahl herunterzuladender Bytes für den Zugriffstest |

#### Restore-Test

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `backup-monitor.restore-test.enabled` | `RESTORE_TEST_ENABLED` | `true` | Restore-Tests aktivieren/deaktivieren |
| `backup-monitor.restore-test.max-parallel` | `RESTORE_TEST_MAX_PARALLEL` | `2` | Maximale Anzahl gleichzeitiger Restore-Tests |
| `backup-monitor.restore-test.timeout-minutes` | `RESTORE_TEST_TIMEOUT` | `45` | Timeout pro Restore-Test in Minuten |

#### Sandbox-Konfiguration (`backup-monitor.restore-test.sandboxes[]`)

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

#### Validierungsabfragen (`backup-monitor.restore-test.validations[]`)

| Eigenschaft | Beschreibung |
|---|---|
| `instance-id` | Referenziert eine konfigurierte Service-Instanz |
| `queries[].description` | Menschenlesbare Beschreibung der Prüfung |
| `queries[].sql` | SQL-Abfrage, die einen einzelnen numerischen Wert in der ersten Spalte zurückgeben muss |
| `queries[].min-result` | Mindestakzeptierter Wert (inklusive) |

#### Aufbewahrung (Retention)

| Eigenschaft | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| `backup-monitor.retention.job-check-entries` | `RETENTION_JOB_CHECK` | `30` | Anzahl beizubehaltender `JOB_CHECK`-Läufe pro Instanz |
| `backup-monitor.retention.restore-test-entries` | `RETENTION_RESTORE_TEST` | `10` | Anzahl beizubehaltender `RESTORE_TEST`-Läufe pro Instanz |
| `backup-monitor.retention.s3-check-entries` | `RETENTION_S3_CHECK` | `30` | Anzahl beizubehaltender S3-Prüfergebnisse pro Instanz |

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

**Backup-Job**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_job_last_status` | Gauge | `1` = letzter Job SUCCEEDED, `0` = anderweitig |
| `backup_job_last_age_hours` | Gauge | Alter des letzten Backups in Stunden; `-1` = kein Job gefunden |
| `backup_job_last_filesize_bytes` | Gauge | Gemeldete Dateigröße des letzten Backups in Bytes |
| `backup_job_success_total` | Counter | Gesamtanzahl erfolgreicher Job-Prüfungen |
| `backup_job_failure_total` | Counter | Gesamtanzahl fehlgeschlagener Job-Prüfungen |

**S3-Verifikation**

| Metrik | Typ | Beschreibung |
|---|---|---|
| `backup_s3_file_exists` | Gauge | `1` = Backup-Datei in S3 gefunden |
| `backup_s3_size_match` | Gauge | `1` = Dateigröße innerhalb der konfigurierten Toleranz |
| `backup_s3_accessible` | Gauge | `1` = Datei-Bytes per Range-Request abrufbar |
| `backup_s3_magic_bytes_valid` | Gauge | `1` = Datei beginnt mit gültigen gzip Magic Bytes (`1F 8B`) |
| `backup_s3_size_deviation_percent` | Gauge | Abweichung zwischen gemeldeter und tatsächlicher Dateigröße in Prozent |
| `backup_s3_all_checks_passed` | Gauge | `1` = alle vier S3-Prüfungen bestanden |
| `backup_s3_file_size_bytes` | Gauge | Tatsächliche Dateigröße in S3 in Bytes |

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
| `s3_check_result` | Detaillierte S3-Verifikationsergebnisse; durch Retention bereinigt |
| `restore_test_result` | Detaillierte Restore-Test-Ergebnisse inkl. Abfrageergebnissen pro Query (JSONB) |
| `sandbox_registry` | Persistente Registry provisionierter CF-Sandbox-Instanzen |
| `stored_token` | Verschlüsselte UAA-Zugriffstoken pro Manager |
| `shedlock` | Verteilte Sperrtabelle (ShedLock) |

---

### Tests ausführen

```bash
# Alle Tests (Docker für Testcontainers erforderlich)
mvn test

# Integrationstests überspringen
mvn test -Dgroups='!integration'
```

Tests verwenden **Testcontainers** (PostgreSQL, MinIO) und **WireMock** – keine externen Dienste erforderlich.
