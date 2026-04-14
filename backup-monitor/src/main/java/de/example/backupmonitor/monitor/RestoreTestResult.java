package de.example.backupmonitor.monitor;

import de.example.backupmonitor.client.AgentClient;
import de.example.backupmonitor.validation.QueryCheckResult;

import java.util.List;

public record RestoreTestResult(
        String instanceId,
        Status status,
        long durationSeconds,
        boolean allValidationsPassed,
        List<QueryCheckResult> validationResults,
        String errorMessage
) {
    public enum Status {
        OK, FAILED, SKIPPED, NO_RESOURCES, TIMEOUT, ERROR
    }

    public static RestoreTestResult ok(String instanceId, List<QueryCheckResult> checks) {
        return new RestoreTestResult(instanceId, Status.OK, 0,
                checks.stream().allMatch(QueryCheckResult::passed), checks, null);
    }

    public static RestoreTestResult ok(String instanceId, List<QueryCheckResult> checks,
                                        long durationSeconds) {
        return new RestoreTestResult(instanceId, Status.OK, durationSeconds,
                checks.stream().allMatch(QueryCheckResult::passed), checks, null);
    }

    public static RestoreTestResult restoreFailed(String instanceId,
                                                   AgentClient.AgentRestoreStatus agentStatus) {
        return new RestoreTestResult(instanceId, Status.FAILED, 0, false,
                List.of(), "Agent status: " + agentStatus);
    }

    public static RestoreTestResult validationFailed(String instanceId,
                                                      List<QueryCheckResult> checks) {
        return new RestoreTestResult(instanceId, Status.FAILED, 0, false, checks, null);
    }

    public static RestoreTestResult skipped(String instanceId) {
        return new RestoreTestResult(instanceId, Status.SKIPPED, 0, false, List.of(), null);
    }

    public static RestoreTestResult noResources(String instanceId, String reason) {
        return new RestoreTestResult(instanceId, Status.NO_RESOURCES, 0, false,
                List.of(), reason);
    }

    public static RestoreTestResult error(String instanceId, String message) {
        return new RestoreTestResult(instanceId, Status.ERROR, 0, false, List.of(), message);
    }
}
