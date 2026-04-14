package de.example.backupmonitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

@Configuration
public class EncryptionConfig {

    @Value("${backup-monitor.encryption.key}")
    private String encryptionKey;

    @Bean
    public TextEncryptor tokenEncryptor() {
        // AES-256-GCM via Spring Security Crypto mit zufälligem Salt
        return Encryptors.delux(encryptionKey, KeyGenerators.string().generateKey());
    }
}
