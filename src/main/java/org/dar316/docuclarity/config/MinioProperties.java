package org.dar316.docuclarity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja połączenia z MinIO.
 *
 * Właściwości z prefiksem docuclarity.minio w application.properties:
 * - endpoint: URL serwera MinIO (np. http://localhost:9000)
 * - accessKey: klucz dostępu
 * - secretKey: klucz tajny
 * - bucket: nazwa bucketu na pliki źródłowe
 */
@ConfigurationProperties(prefix = "docuclarity.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
    public MinioProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "docuclarity.minio.bucket nie może być pusty");
        }
    }
}
