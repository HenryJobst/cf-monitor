package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AgentExecutionResponse {

    private String filename;

    @JsonProperty("filesize_bytes")
    private Long filesizeBytes;

    private String status;
    private String message;
}
