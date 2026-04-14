package de.example.backupmonitor.sandbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxRegistry {

    private final SandboxRegistryRepository repository;

    public Optional<SandboxConnection> findExisting(String instanceId) {
        return repository.findByInstanceId(instanceId)
                .map(this::toConnection);
    }

    public void register(String managerId, String instanceId,
                          SandboxConnection conn, TextEncryptor encryptor) {
        SandboxRegistryEntry entry = repository.findByInstanceId(instanceId)
                .orElse(new SandboxRegistryEntry());
        entry.setManagerId(managerId);
        entry.setInstanceId(instanceId);
        entry.setMode(conn.provisioned() ? SandboxMode.PROVISION : SandboxMode.EXISTING);
        entry.setCfServiceInstanceName(conn.cfServiceInstanceName());
        entry.setSandboxHost(conn.host());
        entry.setSandboxPort(conn.port());
        entry.setSandboxDatabase(conn.database());
        entry.setSandboxUsername(conn.username());
        if (conn.password() != null) {
            entry.setSandboxPasswordEnc(encryptor.encrypt(conn.password()));
        }
        if (conn.provisioned()) {
            entry.setProvisionedAt(Instant.now());
        }
        repository.save(entry);
        log.info("Registered sandbox for instance {} (mode: {})", instanceId, entry.getMode());
    }

    public void remove(String instanceId) {
        repository.findByInstanceId(instanceId).ifPresent(repository::delete);
    }

    private SandboxConnection toConnection(SandboxRegistryEntry entry) {
        return new SandboxConnection(
                entry.getSandboxHost(),
                entry.getSandboxPort(),
                entry.getSandboxDatabase(),
                entry.getSandboxUsername(),
                null, // Passwort entschlüsseln wenn nötig
                entry.getCfServiceInstanceName(),
                entry.getMode() == SandboxMode.PROVISION);
    }
}
