package de.example.backupmonitor.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * Lädt Variablen aus einer .env-Datei im Arbeitsverzeichnis und fügt sie
 * als PropertySource ein – mit niedrigerer Priorität als echte OS-Umgebungsvariablen.
 * Ist keine .env-Datei vorhanden, passiert nichts (ignoreIfMissing).
 */
public class DotenvEnvironmentPostProcessor implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String PROPERTY_SOURCE_NAME = ".env";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        Map<String, Object> properties = new HashMap<>();
        dotenv.entries().forEach(e -> properties.put(e.getKey(), e.getValue()));

        if (properties.isEmpty()) {
            return;
        }

        ConfigurableEnvironment environment = event.getEnvironment();
        environment.getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
}
