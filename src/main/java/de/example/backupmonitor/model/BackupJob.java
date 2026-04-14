package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
public class BackupJob {

    private Object id;

    @JsonProperty("instance_id")
    private String instanceId;

    private JobStatus status;

    private Instant startDate;

    private Instant endDate;

    private FileDestination destination;

    private Map<String, String> files = new HashMap<>();

    @JsonProperty("agentExecutionReponses")
    private Map<String, AgentExecutionResponse> agentExecutionReponses = new HashMap<>();

    private Long filesize;

    public String getIdAsString() {
        return id != null ? id.toString() : null;
    }
}
