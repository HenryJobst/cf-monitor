package de.example.backupmonitor.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.BooleanSupplier;

@Service
@RequiredArgsConstructor
@Transactional
public class ConsecutiveFailuresService {

    private final InstanceJobStateRepository repository;

    public int recordSuccess(String managerId, String instanceId) {
        InstanceJobStateEntity state = findOrCreate(managerId, instanceId);
        state.setConsecutiveFailures(0);
        state.setHasEverSucceeded(true);
        state.setUpdatedAt(Instant.now());
        repository.save(state);
        return 0;
    }

    public int recordFailure(String managerId, String instanceId) {
        InstanceJobStateEntity state = findOrCreate(managerId, instanceId);
        int newCount = state.getConsecutiveFailures() + 1;
        state.setConsecutiveFailures(newCount);
        state.setUpdatedAt(Instant.now());
        repository.save(state);
        return newCount;
    }

    public boolean hasEverSucceeded(String managerId, String instanceId,
                                     BooleanSupplier apiCheck) {
        InstanceJobStateEntity state = findOrCreate(managerId, instanceId);
        if (state.isHasEverSucceeded()) return true;
        boolean result = apiCheck.getAsBoolean();
        if (result) {
            state.setHasEverSucceeded(true);
            state.setUpdatedAt(Instant.now());
            repository.save(state);
        }
        return result;
    }

    private InstanceJobStateEntity findOrCreate(String managerId, String instanceId) {
        return repository.findByManagerIdAndInstanceId(managerId, instanceId)
                .orElseGet(() -> {
                    InstanceJobStateEntity e = new InstanceJobStateEntity();
                    e.setManagerId(managerId);
                    e.setInstanceId(instanceId);
                    e.setConsecutiveFailures(0);
                    e.setHasEverSucceeded(false);
                    e.setUpdatedAt(Instant.now());
                    return e;
                });
    }
}
