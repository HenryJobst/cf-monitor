package de.example.backupmonitor;

import de.example.backupmonitor.config.DotenvEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackupMonitorApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BackupMonitorApplication.class);
        app.addListeners(new DotenvEnvironmentPostProcessor());
        app.run(args);
    }
}
