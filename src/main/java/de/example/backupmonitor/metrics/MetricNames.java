package de.example.backupmonitor.metrics;

public final class MetricNames {

    private MetricNames() {}

    // Plan
    public static final String PLAN_ACTIVE             = "backup_plan_active";
    public static final String PLAN_PAUSED             = "backup_plan_paused";

    // Job
    public static final String JOB_LAST_STATUS         = "backup_job_last_status";
    public static final String JOB_LAST_AGE_HOURS      = "backup_job_last_age_hours";
    public static final String JOB_LAST_FILESIZE        = "backup_job_last_filesize_bytes";
    public static final String JOB_LAST_DURATION_MS     = "backup_job_last_duration_ms";
    public static final String JOB_SUCCESS_TOTAL        = "backup_job_success_total";
    public static final String JOB_FAILURE_TOTAL        = "backup_job_failure_total";

    // Restore
    public static final String RESTORE_LAST_STATUS          = "backup_restore_last_status";
    public static final String RESTORE_LAST_DURATION_SEC    = "backup_restore_last_duration_seconds";
    public static final String RESTORE_VALIDATION_PASSED    = "backup_restore_validation_passed";

    // Monitor
    public static final String MONITOR_LAST_RUN         = "backup_monitor_last_run_timestamp";

    // S3 Verification
    public static final String S3_FILE_EXISTS           = "backup_s3_file_exists";
    public static final String S3_SIZE_MATCH            = "backup_s3_size_match";
    public static final String S3_ACCESSIBLE            = "backup_s3_accessible";
    public static final String S3_MAGIC_BYTES_VALID     = "backup_s3_magic_bytes_valid";
    public static final String S3_SIZE_SHRINK_WARNING    = "backup_s3_size_shrink_warning";
    public static final String S3_SIZE_GROWTH_WARNING    = "backup_s3_size_growth_warning";
    public static final String S3_DURATION_GROWTH_WARNING = "backup_s3_duration_growth_warning";
    public static final String S3_ALL_CHECKS_PASSED     = "backup_s3_all_checks_passed";
    public static final String S3_FILE_SIZE_BYTES       = "backup_s3_file_size_bytes";
}
