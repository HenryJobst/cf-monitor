package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BackupPlan {

    private Object id;

    @JsonProperty("instance_id")
    private String instanceId;

    private boolean paused;
    private boolean active;
    private String status;

    @JsonProperty("backup_agent_url")
    private String backupAgentUrl;

    public String getIdAsString() {
        return id != null ? id.toString() : null;
    }
}
