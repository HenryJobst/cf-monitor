package de.example.backupmonitor.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

@Slf4j
public class CfTokenService {

    private final String managerId;
    private final String uaaEndpoint;
    private final String username;
    private final String password;
    private final TokenRepository tokenRepository;
    private final TextEncryptor encryptor;
    private final RestClient restClient = RestClient.create();

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant accessTokenExpiry;

    public CfTokenService(String managerId, String uaaEndpoint,
                          String username, String password,
                          TokenRepository tokenRepository,
                          TextEncryptor encryptor) {
        this.managerId = managerId;
        this.uaaEndpoint = uaaEndpoint;
        this.username = username;
        this.password = password;
        this.tokenRepository = tokenRepository;
        this.encryptor = encryptor;
    }

    public String getValidAccessToken() {
        if (isLocalCacheValid()) return accessToken;
        if (accessToken == null) loadFromDatabase();
        if (isLocalCacheValid()) return accessToken;
        if (refreshToken != null) {
            try {
                return refreshAndPersist();
            } catch (Exception e) {
                log.warn("Refresh failed for manager {}: {}", managerId, e.getMessage());
                clearStoredRefreshToken();
            }
        }
        return authenticateAndPersist();
    }

    private boolean isLocalCacheValid() {
        return accessToken != null
                && accessTokenExpiry != null
                && Instant.now().isBefore(accessTokenExpiry);
    }

    private void loadFromDatabase() {
        tokenRepository.findByManagerId(managerId).ifPresent(stored -> {
            if (stored.getAccessTokenEnc() != null)
                this.accessToken = encryptor.decrypt(stored.getAccessTokenEnc());
            if (stored.getRefreshTokenEnc() != null)
                this.refreshToken = encryptor.decrypt(stored.getRefreshTokenEnc());
            this.accessTokenExpiry = stored.getAccessTokenExpiry();
            log.debug("Loaded token for manager {} from DB (valid: {})",
                    managerId, stored.isAccessTokenValid());
        });
    }

    private synchronized String refreshAndPersist() {
        if (isLocalCacheValid()) return accessToken;
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", this.refreshToken);
        TokenResponse r = postToUaa(params);
        updateCache(r);
        persistTokens();
        return accessToken;
    }

    private synchronized String authenticateAndPersist() {
        if (isLocalCacheValid()) return accessToken;
        log.info("Authenticating manager {} at {}", managerId, uaaEndpoint);
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "password");
        params.add("username", username);
        params.add("password", password);
        TokenResponse r = postToUaa(params);
        updateCache(r);
        persistTokens();
        return accessToken;
    }

    private void updateCache(TokenResponse r) {
        this.accessToken = r.accessToken();
        this.refreshToken = r.refreshToken();
        this.accessTokenExpiry = Instant.now().plusSeconds(r.expiresIn() - 300);
    }

    @Transactional
    protected void persistTokens() {
        StoredToken stored = tokenRepository
                .findByManagerId(managerId)
                .orElse(new StoredToken());
        stored.setManagerId(managerId);
        stored.setUaaEndpoint(uaaEndpoint);
        stored.setAccessTokenEnc(encryptor.encrypt(accessToken));
        if (refreshToken != null) {
            stored.setRefreshTokenEnc(encryptor.encrypt(refreshToken));
        }
        stored.setAccessTokenExpiry(accessTokenExpiry);
        stored.setUpdatedAt(Instant.now());
        tokenRepository.save(stored);
    }

    @Transactional
    protected void clearStoredRefreshToken() {
        tokenRepository.findByManagerId(managerId).ifPresent(t -> {
            t.setRefreshTokenEnc(null);
            tokenRepository.save(t);
        });
        this.refreshToken = null;
    }

    private TokenResponse postToUaa(MultiValueMap<String, String> params) {
        return restClient.post()
                .uri(uaaEndpoint + "/oauth/token")
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    h.setBasicAuth("cf", "");
                })
                .body(params)
                .retrieve()
                .body(TokenResponse.class);
    }
}
