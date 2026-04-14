package de.example.backupmonitor.validation;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.sandbox.SandboxConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DatabaseContentCheckerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSTANCE_ID = "inst-1";

    private SandboxConnection sandbox;

    @BeforeEach
    void setUp() throws Exception {
        sandbox = new SandboxConnection(
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword(),
                null, false);

        // Testtabelle anlegen und befüllen
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_data");
            stmt.execute("CREATE TABLE test_data (id SERIAL PRIMARY KEY, value TEXT)");
            stmt.execute("INSERT INTO test_data (value) VALUES ('row1'), ('row2'), ('row3')");
        }
    }

    // ── passing query ─────────────────────────────────────────────────────────

    @Test
    void runChecks_passingQuery_returnsOk() {
        DatabaseContentChecker checker = buildChecker(
                "COUNT(*) >= 1", "SELECT COUNT(*) FROM test_data", 1L);

        List<QueryCheckResult> results = checker.runChecks(sandbox, INSTANCE_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).result()).isEqualTo(3L);
    }

    // ── failing query ─────────────────────────────────────────────────────────

    @Test
    void runChecks_resultBelowMinimum_returnsFailed() {
        DatabaseContentChecker checker = buildChecker(
                "need 100 rows", "SELECT COUNT(*) FROM test_data", 100L);

        List<QueryCheckResult> results = checker.runChecks(sandbox, INSTANCE_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).errorMessage()).contains("3").contains("100");
    }

    // ── no queries configured ─────────────────────────────────────────────────

    @Test
    void runChecks_noQueriesForInstance_returnsEmptyList() {
        MonitoringConfig config = new MonitoringConfig();
        // keine Validierungen konfiguriert
        DatabaseContentChecker checker = new DatabaseContentChecker(config);

        List<QueryCheckResult> results = checker.runChecks(sandbox, "unknown-instance");

        assertThat(results).isEmpty();
    }

    // ── multiple queries ──────────────────────────────────────────────────────

    @Test
    void runChecks_multipleQueries_returnsResultForEach() {
        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ValidationConfig validation = new MonitoringConfig.ValidationConfig();
        validation.setInstanceId(INSTANCE_ID);

        MonitoringConfig.QueryConfig q1 = new MonitoringConfig.QueryConfig();
        q1.setDescription("check rows >= 1");
        q1.setSql("SELECT COUNT(*) FROM test_data");
        q1.setMinResult(1L);

        MonitoringConfig.QueryConfig q2 = new MonitoringConfig.QueryConfig();
        q2.setDescription("check rows >= 10");
        q2.setSql("SELECT COUNT(*) FROM test_data");
        q2.setMinResult(10L);

        validation.setQueries(List.of(q1, q2));
        config.getRestoreTest().setValidations(List.of(validation));

        DatabaseContentChecker checker = new DatabaseContentChecker(config);
        List<QueryCheckResult> results = checker.runChecks(sandbox, INSTANCE_ID);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(1).passed()).isFalse();
    }

    // ── Hilfsmethode ─────────────────────────────────────────────────────────

    private DatabaseContentChecker buildChecker(String description, String sql, long minResult) {
        MonitoringConfig config = new MonitoringConfig();
        MonitoringConfig.ValidationConfig validation = new MonitoringConfig.ValidationConfig();
        validation.setInstanceId(INSTANCE_ID);

        MonitoringConfig.QueryConfig query = new MonitoringConfig.QueryConfig();
        query.setDescription(description);
        query.setSql(sql);
        query.setMinResult(minResult);
        validation.setQueries(List.of(query));

        config.getRestoreTest().setValidations(List.of(validation));
        return new DatabaseContentChecker(config);
    }
}
