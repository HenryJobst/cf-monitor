package de.example.backupmonitor.s3;

import de.example.backupmonitor.model.AgentExecutionResponse;
import de.example.backupmonitor.model.BackupJob;
import de.example.backupmonitor.model.JobStatus;
import de.example.backupmonitor.model.S3FileDestination;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@ExtendWith(MockitoExtension.class)
class S3VerificationServiceIntegrationTest {

    static final String BUCKET = "test-backup-bucket";
    static final String FILE_KEY = "backup/test-backup.tar.gz";

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @Mock
    private S3CheckResultRepository repository;

    private S3ClientFactory clientFactory;
    private S3VerificationService service;
    private MeterRegistry meterRegistry;
    private S3Client adminClient;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        de.example.backupmonitor.metrics.MetricsPublisher metricsPublisher =
                new de.example.backupmonitor.metrics.MetricsPublisher(meterRegistry);

        clientFactory = new S3ClientFactory();
        service = new S3VerificationService(clientFactory, repository, metricsPublisher);
        injectField(service, "sizeTolerance", 10);
        injectField(service, "accessibilityBytes", 16);

        adminClient = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();

        try {
            adminClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // Bucket existiert bereits vom vorherigen Test — kein Problem
        }
    }

    @AfterEach
    void tearDown() {
        try {
            adminClient.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(FILE_KEY).build());
        } catch (Exception ignored) {
            // Datei existiert evtl. nicht
        }
    }

    @Test
    void verify_fileExists_allChecksPass() {
        // gzip magic bytes
        byte[] content = new byte[1024];
        content[0] = 0x1F;
        content[1] = (byte) 0x8B;

        adminClient.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(FILE_KEY).build(),
                RequestBody.fromBytes(content));

        BackupJob job = buildJob(content.length);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        S3CheckResult result = service.verify("mgr-1", "inst-1", "pg-test", job);

        assertThat(result.isExists()).isTrue();
        assertThat(result.isAccessible()).isTrue();
        assertThat(result.isMagicBytesValid()).isTrue();
        assertThat(result.isSizeMatchWithinTolerance()).isTrue();
        assertThat(result.isAllPassed()).isTrue();
        assertThat(result.isS3Verified()).isTrue();

        verify(repository).save(any(S3CheckResultEntity.class));
    }

    @Test
    void verify_fileMissing_existsFalse() {
        BackupJob job = buildJob(1024);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        S3CheckResult result = service.verify("mgr-1", "inst-1", "pg-test", job);

        assertThat(result.isExists()).isFalse();
        assertThat(result.isAllPassed()).isFalse();
        assertThat(result.isS3Verified()).isFalse();
    }

    @Test
    void verify_wrongMagicBytes_magicBytesFalse() {
        byte[] content = new byte[1024]; // keine gzip magic bytes (alle 0x00)

        adminClient.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(FILE_KEY).build(),
                RequestBody.fromBytes(content));

        BackupJob job = buildJob(content.length);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        S3CheckResult result = service.verify("mgr-1", "inst-1", "pg-test", job);

        assertThat(result.isExists()).isTrue();
        assertThat(result.isAccessible()).isTrue();
        assertThat(result.isMagicBytesValid()).isFalse();
        // magicBytesValid ist Warning, nicht harter Fehler für allPassed
        assertThat(result.isAllPassed()).isFalse();
        // aber S3Verified = exists && accessible && sizeMatch
        assertThat(result.isS3Verified()).isTrue();
    }

    @Test
    void verify_sizeMismatch_sizeMatchFalse() {
        byte[] content = new byte[1024];
        content[0] = 0x1F;
        content[1] = (byte) 0x8B;

        adminClient.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(FILE_KEY).build(),
                RequestBody.fromBytes(content));

        // Berichtete Größe weicht stark ab (50% > 10% Toleranz)
        BackupJob job = buildJob(2048);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        S3CheckResult result = service.verify("mgr-1", "inst-1", "pg-test", job);

        assertThat(result.isExists()).isTrue();
        assertThat(result.isSizeMatchWithinTolerance()).isFalse();
        assertThat(result.getSizeDeviationPercent()).isGreaterThan(10.0);
    }

    private BackupJob buildJob(long filesize) {
        BackupJob job = new BackupJob();
        job.setId("test-job-001");
        job.setInstanceId("inst-1");
        job.setStatus(JobStatus.SUCCEEDED);

        S3FileDestination dest = new S3FileDestination();
        dest.setBucket(BUCKET);
        dest.setEndpoint(minio.getS3URL());
        dest.setAuthKey("minioadmin");
        dest.setAuthSecret("minioadmin");
        dest.setSkipSSL(false);
        job.setDestination(dest);

        job.setFiles(Map.of("file", FILE_KEY));

        AgentExecutionResponse agentResp = new AgentExecutionResponse();
        agentResp.setFilename(FILE_KEY);
        agentResp.setFilesizeBytes(filesize);
        job.setAgentExecutionReponses(Map.of("agent-1", agentResp));

        return job;
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field " + fieldName, e);
        }
    }
}
