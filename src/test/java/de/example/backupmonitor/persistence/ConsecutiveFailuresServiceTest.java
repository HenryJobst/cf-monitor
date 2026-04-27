package de.example.backupmonitor.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsecutiveFailuresServiceTest {

    @Mock
    private InstanceJobStateRepository repository;

    private ConsecutiveFailuresService service;

    @BeforeEach
    void setUp() {
        service = new ConsecutiveFailuresService(repository);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private InstanceJobStateEntity stateWith(int failures, boolean hasEverSucceeded) {
        InstanceJobStateEntity e = new InstanceJobStateEntity();
        e.setManagerId("mgr");
        e.setInstanceId("inst");
        e.setConsecutiveFailures(failures);
        e.setHasEverSucceeded(hasEverSucceeded);
        return e;
    }

    // ── recordSuccess ─────────────────────────────────────────────────────────

    @Test
    void recordSuccess_resetsCounterToZero() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(3, false)));

        int result = service.recordSuccess("mgr", "inst");

        assertThat(result).isZero();
        verify(repository).save(argThat(e -> e.getConsecutiveFailures() == 0
                && e.isHasEverSucceeded()));
    }

    @Test
    void recordSuccess_setsHasEverSucceeded() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(0, false)));

        service.recordSuccess("mgr", "inst");

        verify(repository).save(argThat(InstanceJobStateEntity::isHasEverSucceeded));
    }

    @Test
    void recordSuccess_createsNewStateIfAbsent() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.empty());

        int result = service.recordSuccess("mgr", "inst");

        assertThat(result).isZero();
        verify(repository).save(any());
    }

    // ── recordFailure ─────────────────────────────────────────────────────────

    @Test
    void recordFailure_incrementsCounter() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(2, false)));

        int result = service.recordFailure("mgr", "inst");

        assertThat(result).isEqualTo(3);
        verify(repository).save(argThat(e -> e.getConsecutiveFailures() == 3));
    }

    @Test
    void recordFailure_createsNewStateIfAbsent_returnsOne() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.empty());

        int result = service.recordFailure("mgr", "inst");

        assertThat(result).isEqualTo(1);
    }

    // ── hasEverSucceeded ──────────────────────────────────────────────────────

    @Test
    void hasEverSucceeded_flagAlreadyTrue_returnsTrueWithoutApiCall() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(0, true)));

        boolean result = service.hasEverSucceeded("mgr", "inst", () -> {
            throw new AssertionError("API should not be called");
        });

        assertThat(result).isTrue();
    }

    @Test
    void hasEverSucceeded_flagFalseApiReturnsTrue_persistsAndReturnsTrue() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(0, false)));

        boolean result = service.hasEverSucceeded("mgr", "inst", () -> true);

        assertThat(result).isTrue();
        verify(repository).save(argThat(InstanceJobStateEntity::isHasEverSucceeded));
    }

    @Test
    void hasEverSucceeded_flagFalseApiReturnsFalse_returnsFalse() {
        when(repository.findByManagerIdAndInstanceId("mgr", "inst"))
                .thenReturn(Optional.of(stateWith(0, false)));

        boolean result = service.hasEverSucceeded("mgr", "inst", () -> false);

        assertThat(result).isFalse();
        verify(repository, never()).save(any());
    }
}
