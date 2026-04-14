package de.example.backupmonitor.auth;

import de.example.backupmonitor.config.MonitoringConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CfTokenServiceRegistry {

    private final Map<String, CfTokenService> services;

    public CfTokenServiceRegistry(MonitoringConfig config,
                                   TokenRepository tokenRepository,
                                   TextEncryptor tokenEncryptor) {
        this.services = config.getManagers().stream()
                .collect(Collectors.toMap(
                        MonitoringConfig.ManagerConfig::getId,
                        m -> new CfTokenService(
                                m.getId(),
                                m.getCf().getUaaEndpoint(),
                                m.getCf().getServiceAccount().getUsername(),
                                m.getCf().getServiceAccount().getPassword(),
                                tokenRepository,
                                tokenEncryptor)));
        log.info("Initialized CfTokenServiceRegistry with {} manager(s)", services.size());
    }

    public String getToken(String managerId) {
        CfTokenService service = services.get(managerId);
        if (service == null) {
            throw new IllegalArgumentException("No token service for manager: " + managerId);
        }
        return service.getValidAccessToken();
    }
}
