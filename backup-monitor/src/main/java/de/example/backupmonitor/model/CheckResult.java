package de.example.backupmonitor.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CheckResult {

    private boolean ok;
    private String message;
    private String instanceId;

    public static CheckResult ok(String instanceId) {
        return CheckResult.builder().ok(true).instanceId(instanceId).build();
    }

    public static CheckResult failed(String instanceId, String message) {
        return CheckResult.builder().ok(false).instanceId(instanceId).message(message).build();
    }
}
