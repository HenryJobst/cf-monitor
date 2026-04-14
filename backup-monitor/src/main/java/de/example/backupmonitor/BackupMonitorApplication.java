package de.example.backupmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackupMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackupMonitorApplication.class, args);
    }
}
