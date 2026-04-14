package de.example.backupmonitor.validation;

import de.example.backupmonitor.config.MonitoringConfig;
import de.example.backupmonitor.sandbox.SandboxConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseContentChecker {

    private static final Pattern SELECT_ONLY = Pattern.compile(
            "^\\s*SELECT\\b[^;]*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MonitoringConfig config;

    public List<QueryCheckResult> runChecks(SandboxConnection sandbox, String instanceId) {
        List<MonitoringConfig.QueryConfig> queries = findQueriesForInstance(instanceId);
        List<QueryCheckResult> results = new ArrayList<>();

        String jdbcUrl = "jdbc:postgresql://" + sandbox.host() + ":" + sandbox.port()
                + "/" + sandbox.database();

        try (Connection conn = DriverManager.getConnection(jdbcUrl,
                sandbox.username(), sandbox.password())) {

            for (MonitoringConfig.QueryConfig q : queries) {
                results.add(runSingleCheck(conn, q));
            }
        } catch (Exception e) {
            log.error("Failed to connect to sandbox for validation: {}", e.getMessage());
            results.add(QueryCheckResult.failed("connection", "", 0, 0, e.getMessage()));
        }

        return results;
    }

    private QueryCheckResult runSingleCheck(Connection conn, MonitoringConfig.QueryConfig q) {
        if (!SELECT_ONLY.matcher(q.getSql()).matches()) {
            log.error("Validation query rejected – only SELECT allowed: {}", q.getDescription());
            return QueryCheckResult.failed(q.getDescription(), q.getSql(), 0, q.getMinResult(),
                    "Query rejected: only SELECT statements are permitted");
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(q.getSql())) {
            long result = rs.next() ? rs.getLong(1) : 0;
            if (result >= q.getMinResult()) {
                log.debug("Validation '{}' passed: {} >= {}", q.getDescription(), result, q.getMinResult());
                return QueryCheckResult.ok(q.getDescription(), q.getSql(), result, q.getMinResult());
            } else {
                log.warn("Validation '{}' failed: {} < {}", q.getDescription(), result, q.getMinResult());
                return QueryCheckResult.failed(q.getDescription(), q.getSql(), result, q.getMinResult(),
                        "Result " + result + " < minimum " + q.getMinResult());
            }
        } catch (Exception e) {
            log.error("Validation query failed: {}", e.getMessage());
            return QueryCheckResult.failed(q.getDescription(), q.getSql(), 0, q.getMinResult(), e.getMessage());
        }
    }

    private List<MonitoringConfig.QueryConfig> findQueriesForInstance(String instanceId) {
        return config.getRestoreTest().getValidations().stream()
                .filter(v -> instanceId.equals(v.getInstanceId()))
                .findFirst()
                .map(MonitoringConfig.ValidationConfig::getQueries)
                .orElse(List.of());
    }
}
