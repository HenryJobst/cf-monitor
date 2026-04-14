package de.example.backupmonitor.s3;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface S3CheckResultRepository extends JpaRepository<S3CheckResultEntity, Long> {

    @Query("""
            SELECT e FROM S3CheckResultEntity e
            WHERE e.instanceId = :instanceId AND e.allPassed = true
            ORDER BY e.checkedAt DESC
            LIMIT 1
            """)
    Optional<S3CheckResultEntity> findLatestPassedForInstance(@Param("instanceId") String instanceId);

    @Query("""
            SELECT e FROM S3CheckResultEntity e
            WHERE e.instanceId = :instanceId
            ORDER BY e.checkedAt DESC
            LIMIT 1
            """)
    Optional<S3CheckResultEntity> findLatestForInstance(@Param("instanceId") String instanceId);

    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM s3_check_result
            WHERE id IN (
                SELECT id FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY instance_id
                               ORDER BY checked_at DESC
                           ) AS rn
                    FROM s3_check_result
                ) ranked
                WHERE rn > :keepN
            )
            """)
    void deleteOldEntriesPerInstance(@Param("keepN") int keepN);
}
