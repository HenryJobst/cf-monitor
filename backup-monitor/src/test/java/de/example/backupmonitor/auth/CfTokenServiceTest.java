package de.example.backupmonitor.auth;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.time.Instant;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WireMockTest
@ExtendWith(MockitoExtension.class)
class CfTokenServiceTest {

    private static final String MANAGER_ID = "mgr-1";

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private TextEncryptor encryptor;

    private CfTokenService tokenService;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        when(encryptor.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        lenient().when(encryptor.decrypt(any())).thenAnswer(inv ->
                inv.getArgument(0, String.class).replaceFirst("^enc:", ""));

        tokenService = new CfTokenService(
                MANAGER_ID,
                "http://localhost:" + wm.getHttpPort(),
                "test-user",
                "test-pass",
                tokenRepository,
                encryptor);
    }

    @Test
    void getValidAccessToken_noStoredToken_fetchesViaPasswordGrant() {
        when(tokenRepository.findByManagerId(MANAGER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubFor(post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=password"))
                .withRequestBody(containing("username=test-user"))
                .withHeader("Authorization", equalTo("Basic Y2Y6"))
                .willReturn(okJson("""
                        {"access_token":"fresh-token","refresh_token":"fresh-refresh","expires_in":3600}
                        """)));

        String token = tokenService.getValidAccessToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(tokenRepository).save(any(StoredToken.class));
    }

    @Test
    void getValidAccessToken_expiredToken_usesRefreshGrant() {
        StoredToken stored = new StoredToken();
        stored.setManagerId(MANAGER_ID);
        stored.setUaaEndpoint("http://uaa.example.com");
        stored.setAccessTokenEnc("enc:old-token");
        stored.setRefreshTokenEnc("enc:my-refresh");
        stored.setAccessTokenExpiry(Instant.now().minusSeconds(60));
        stored.setUpdatedAt(Instant.now());

        when(tokenRepository.findByManagerId(MANAGER_ID))
                .thenReturn(Optional.of(stored))
                .thenReturn(Optional.of(stored));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubFor(post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=my-refresh"))
                .willReturn(okJson("""
                        {"access_token":"refreshed-token","refresh_token":"new-refresh","expires_in":3600}
                        """)));

        String token = tokenService.getValidAccessToken();

        assertThat(token).isEqualTo("refreshed-token");
        verify(postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=refresh_token")));
    }

    @Test
    void getValidAccessToken_refreshFails_fallsBackToPasswordGrant() {
        StoredToken stored = new StoredToken();
        stored.setManagerId(MANAGER_ID);
        stored.setUaaEndpoint("http://uaa.example.com");
        stored.setAccessTokenEnc("enc:old-token");
        stored.setRefreshTokenEnc("enc:bad-refresh");
        stored.setAccessTokenExpiry(Instant.now().minusSeconds(60));
        stored.setUpdatedAt(Instant.now());

        when(tokenRepository.findByManagerId(MANAGER_ID)).thenReturn(Optional.of(stored));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubFor(post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(aResponse().withStatus(401)));

        stubFor(post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=password"))
                .willReturn(okJson("""
                        {"access_token":"password-token","refresh_token":"new-refresh","expires_in":3600}
                        """)));

        String token = tokenService.getValidAccessToken();

        assertThat(token).isEqualTo("password-token");
    }

    @Test
    void getValidAccessToken_validCachedToken_noHttpCall() {
        when(tokenRepository.findByManagerId(MANAGER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubFor(post(urlEqualTo("/oauth/token"))
                .willReturn(okJson("""
                        {"access_token":"cached-token","refresh_token":"r","expires_in":3600}
                        """)));

        tokenService.getValidAccessToken();
        tokenService.getValidAccessToken();

        verify(exactly(1), postRequestedFor(urlEqualTo("/oauth/token")));
    }
}
