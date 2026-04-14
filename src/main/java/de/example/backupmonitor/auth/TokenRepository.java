package de.example.backupmonitor.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TokenRepository extends JpaRepository<StoredToken, Long> {

    Optional<StoredToken> findByManagerId(String managerId);
}
