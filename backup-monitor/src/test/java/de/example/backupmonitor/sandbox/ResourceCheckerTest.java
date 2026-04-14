package de.example.backupmonitor.sandbox;

import de.example.backupmonitor.client.CfApiClient;
import de.example.backupmonitor.config.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ResourceCheckerTest {

    @Mock
    private CfApiClient cfApiClient;

    @InjectMocks
    private ResourceChecker resourceChecker;

    @Test
    void check_mvpAlwaysReturnsSufficient() {
        MonitoringConfig.ProvisionConfig cfg = new MonitoringConfig.ProvisionConfig();
        cfg.setOrg("test-org");

        ResourceCheckResult result = resourceChecker.check("mgr-1", cfg);

        assertThat(result.hasSufficientQuota()).isTrue();
        assertThat(result.reason()).isNull();
    }
}
