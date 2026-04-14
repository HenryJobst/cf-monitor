package de.example.backupmonitor.sandbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SandboxRegistryRepository extends JpaRepository<SandboxRegistryEntry, Long> {

    Optional<SandboxRegistryEntry> findByInstanceId(String instanceId);
}
