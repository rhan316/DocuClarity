package org.dar316.docuclarity.config;

import org.dar316.docuclarity.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inicjalizacja zasobów przy starcie aplikacji.
 *
 * Tworzy bucket MinIO jeśli nie istnieje — bezpieczne idempotentne wywołanie.
 */
@Configuration
public class ResourceInitializer {

    private static final Logger log = LoggerFactory.getLogger(ResourceInitializer.class);

    @Bean
    CommandLineRunner initializeMinioBucket(MinioStorageService minioStorageService) {
        return args -> {
            log.info("Inicjalizacja bucketu MinIO...");
            minioStorageService.initializeBucket();
            log.info("MinIO gotowy");
        };
    }
}
