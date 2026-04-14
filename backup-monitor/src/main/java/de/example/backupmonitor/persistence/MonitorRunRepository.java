package de.example.backupmonitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MonitorRunRepository extends JpaRepository<MonitorRun, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM monitor_run
            WHERE id IN (
                SELECT id FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY manager_id, instance_id, run_type
                               ORDER BY started_at DESC
                           ) AS rn
                    FROM monitor_run
                    WHERE run_type = :runType
                ) ranked
                WHERE rn > :keepN
            )
            """)
    void deleteOldEntriesPerInstanceAndType(@Param("runType") String runType,
                                             @Param("keepN") int keepN);

    @Query("SELECT m FROM MonitorRun m WHERE m.startedAt = (SELECT MAX(m2.startedAt) FROM MonitorRun m2 WHERE m2.instanceId = m.instanceId AND m2.runType = m.runType)")
    List<MonitorRun> findLatestPerInstance();
}
