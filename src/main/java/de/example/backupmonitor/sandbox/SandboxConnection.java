package de.example.backupmonitor.sandbox;

public record SandboxConnection(
        String host,
        int port,
        String database,
        String username,
        String password,
        String cfServiceInstanceName,
        boolean provisioned
) {}
