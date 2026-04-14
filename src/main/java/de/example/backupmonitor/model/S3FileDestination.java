package de.example.backupmonitor.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class S3FileDestination extends FileDestination {

    private String type = "S3";
    private String bucket;
    private String endpoint;
    private String region;
    private String authKey;
    private String authSecret;
    private Boolean skipSSL;

    @Override
    public String getType() {
        return type;
    }
}
