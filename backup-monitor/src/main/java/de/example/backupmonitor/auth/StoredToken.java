package de.example.backupmonitor.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "stored_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_stored_token_manager",
                columnNames = "manager_id"))
@Getter
@Setter
public class StoredToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_id", nullable = false, length = 128)
    private String managerId;

    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "access_token_expiry")
    private Instant accessTokenExpiry;

    @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
    private String refreshTokenEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "uaa_endpoint", nullable = false, length = 512)
    private String uaaEndpoint;

    public boolean isAccessTokenValid() {
        return accessTokenEnc != null
                && accessTokenExpiry != null
                && Instant.now().isBefore(accessTokenExpiry);
    }

    public boolean hasRefreshToken() {
        return refreshTokenEnc != null && !refreshTokenEnc.isBlank();
    }
}
