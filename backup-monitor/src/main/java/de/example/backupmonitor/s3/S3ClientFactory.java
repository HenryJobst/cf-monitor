package de.example.backupmonitor.s3;

import de.example.backupmonitor.model.S3FileDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Component
@Slf4j
public class S3ClientFactory {

    public S3Client createClient(S3FileDestination destination) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                destination.getAuthKey(),
                destination.getAuthSecret());

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(resolveRegion(destination));

        if (destination.getEndpoint() != null && !destination.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(destination.getEndpoint()))
                    .forcePathStyle(true);
        }

        if (Boolean.TRUE.equals(destination.getSkipSSL())) {
            String endpoint = destination.getEndpoint();
            boolean isInternalOrUnencrypted = endpoint != null && (
                    endpoint.startsWith("http://") ||
                    endpoint.contains("localhost") ||
                    endpoint.contains("127.0.0.1") ||
                    endpoint.contains(".internal"));
            if (!isInternalOrUnencrypted) {
                log.error("SSL validation disabled for non-internal S3 endpoint '{}'. "
                        + "This is a security risk – use a custom CA truststore instead.", endpoint);
            } else {
                log.warn("SSL validation disabled for S3 endpoint: {}", endpoint);
            }
            builder.httpClient(buildInsecureHttpClient());
        }

        return builder.build();
    }

    private Region resolveRegion(S3FileDestination dest) {
        if (dest.getRegion() != null && !dest.getRegion().isBlank()) {
            return Region.of(dest.getRegion());
        }
        return Region.US_EAST_1;
    }

    private software.amazon.awssdk.http.SdkHttpClient buildInsecureHttpClient() {
        try {
            TrustManager insecure = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{insecure}, new SecureRandom());

            return ApacheHttpClient.builder()
                    .tlsTrustManagersProvider(() -> new TrustManager[]{insecure})
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure HTTP client", e);
        }
    }
}
