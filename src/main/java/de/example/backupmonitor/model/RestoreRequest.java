package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RestoreRequest {

    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("instance_id")
    private String instanceId;

    private FileDestination destination;
    private String filename;

    @JsonProperty("target_host")
    private String targetHost;

    @JsonProperty("target_port")
    private int targetPort;

    @JsonProperty("target_database")
    private String targetDatabase;

    @JsonProperty("target_username")
    private String targetUsername;

    @JsonProperty("target_password")
    private String targetPassword;
}
