package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AgentExecutionResponse {

    private String filename;

    private FilesizeInfo filesize;

    @JsonProperty("execution_time_ms")
    private Long executionTimeMs;

    private String status;
    private String message;

    public Long getFilesizeBytes() {
        return filesize != null ? filesize.size() : null;
    }

    public record FilesizeInfo(Long size, String unit) {}
}
