package de.example.backupmonitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstanceJobStateRepository extends JpaRepository<InstanceJobStateEntity, Long> {

    Optional<InstanceJobStateEntity> findByManagerIdAndInstanceId(String managerId, String instanceId);
}
