package de.example.backupmonitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RestoreTestResultRepository extends JpaRepository<RestoreTestResultEntity, Long> {
}
