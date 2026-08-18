package org.dar316.docuclarity.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Konfiguracja klienta MinIO.
 *
 * Tworzy bean MinioClient na podstawie właściwości z docuclarity.minio.*
 *
 * Bean ObjectMapper (Jackson 2) jest wymagany przez MinIO SDK 8.5.17, które
 * używa com.fasterxml.jackson — Spring Boot 4 domyślnie konfiguruje Jackson 3
 * (tools.jackson), więc bean ObjectMapper Jackson 2 nie istnieje automatycznie.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }
}
