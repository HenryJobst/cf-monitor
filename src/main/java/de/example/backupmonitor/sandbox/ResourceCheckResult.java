package de.example.backupmonitor.sandbox;

public record ResourceCheckResult(boolean hasSufficientQuota, String reason) {

    public static ResourceCheckResult sufficient() {
        return new ResourceCheckResult(true, null);
    }

    public static ResourceCheckResult insufficient(String reason) {
        return new ResourceCheckResult(false, reason);
    }
}
