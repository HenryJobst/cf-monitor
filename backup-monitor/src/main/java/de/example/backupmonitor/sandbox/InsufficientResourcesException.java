package de.example.backupmonitor.sandbox;

public class InsufficientResourcesException extends RuntimeException {

    public InsufficientResourcesException(String reason) {
        super("Insufficient CF resources: " + reason);
    }
}
