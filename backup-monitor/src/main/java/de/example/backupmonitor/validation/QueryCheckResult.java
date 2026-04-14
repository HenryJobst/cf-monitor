package de.example.backupmonitor.validation;

public record QueryCheckResult(
        String description,
        String sql,
        long result,
        long minResult,
        boolean passed,
        String errorMessage
) {
    public static QueryCheckResult ok(String description, String sql, long result, long minResult) {
        return new QueryCheckResult(description, sql, result, minResult, true, null);
    }

    public static QueryCheckResult failed(String description, String sql,
                                           long result, long minResult, String error) {
        return new QueryCheckResult(description, sql, result, minResult, false, error);
    }
}
