package de.example.backupmonitor.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = S3FileDestination.class, name = "s3")
})
public abstract class FileDestination {

    public abstract String getType();
}
