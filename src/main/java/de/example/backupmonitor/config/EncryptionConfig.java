package de.example.backupmonitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

@Configuration
public class EncryptionConfig {

    @Value("${backup-monitor.encryption.key}")
    private String encryptionKey;

    @Value("${backup-monitor.encryption.salt}")
    private String encryptionSalt;

    @Bean
    public TextEncryptor tokenEncryptor() {
        // AES-256-GCM via Spring Security Crypto mit festem Salt (aus Konfiguration).
        // Salt muss über Deployments hinweg konstant bleiben, damit gespeicherte Tokens
        // nach einem Neustart noch entschlüsselt werden können.
        return Encryptors.delux(encryptionKey, encryptionSalt);
    }
}
