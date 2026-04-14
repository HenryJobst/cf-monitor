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

    @JsonProperty("start_date")
    private Instant startDate;

    @JsonProperty("end_date")
    private Instant endDate;

    private FileDestination destination;

    private Map<String, String> files = new HashMap<>();

    @JsonProperty("agent_execution_reponses")
    private Map<String, AgentExecutionResponse> agentExecutionReponses = new HashMap<>();

    private Long filesize;

    public String getIdAsString() {
        return id != null ? id.toString() : null;
    }
}
