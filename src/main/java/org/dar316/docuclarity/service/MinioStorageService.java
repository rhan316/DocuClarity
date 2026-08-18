package org.dar316.docuclarity.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.dar316.docuclarity.config.MinioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Serwis przechowywania plików w MinIO.
 *
 * Odpowiada za:
 * - inicjalizację bucketu (tworzenie jeśli nie istnieje)
 * - upload pliku źródłowego do MinIO
 * - usuwanie pliku (kompensacja przy błędzie zapisu do DB)
 *
 * Klucz obiektu ma format: documents/{documentId}/source
 * Pozwala to na późniejsze dodanie wyników per strona w tej samej ścieżce.
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.bucket = properties.bucket();
    }

    /**
     * Inicjalizuje bucket jeśli nie istnieje. Wywoływane przy starcie aplikacji.
     */
    public void initializeBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Utworzono bucket MinIO: {}", bucket);
            }
        } catch (Exception e) {
            throw new MinioStorageException(
                    "Nie udało się zainicjalizować bucketu MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Uploaduje plik do MinIO.
     *
     * @param storageKey   klucz obiektu (np. documents/{id}/source)
     * @param inputStream  zawartość pliku
     * @param contentType  typ MIME pliku
     * @param contentLength długość pliku w bajtach (-1 jeśli nieznana)
     */
    public void uploadFile(String storageKey,
                           InputStream inputStream,
                           String contentType,
                           long contentLength) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(inputStream, contentLength, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new MinioStorageException(
                    "Błąd uploadu pliku do MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Usuwa plik z MinIO. Używane jako kompensacja gdy zapis do DB nie powiedzie się.
     *
     * @param storageKey klucz obiektu do usunięcia
     */
    public void deleteFile(String storageKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
            log.info("Usunięto plik z MinIO (kompensacja): {}", storageKey);
        } catch (ErrorResponseException e) {
            // Object not found — ignorujemy, celem kompensacji już osiągnięto
            log.debug("Plik nie istnieje w MinIO (pomijam): {}", storageKey);
        } catch (Exception e) {
            log.error("Błąd usuwania pliku z MinIO: {}", storageKey, e);
        }
    }

    /** Zwraca nazwę bucketu */
    public String getBucket() {
        return bucket;
    }

    /**
     * Pobiera obiekt z MinIO jako InputStream.
     *
     * @param storageKey klucz obiektu (np. documents/{id}/source)
     * @return strumień z zawartością pliku
     * @throws MinioStorageException gdy pobranie się nie powiedzie
     */
    public InputStream downloadFile(String storageKey) {
        try {
            return minioClient.getObject(
                    io.minio.GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(storageKey)
                            .build());
        } catch (Exception e) {
            throw new MinioStorageException(
                    "Błąd pobrania pliku z MinIO: " + storageKey + " — " + e.getMessage(), e);
        }
    }

    /**
     * Uploaduje zawartość tekstową (JSON) do MinIO.
     *
     * @param storageKey klucz obiektu (np. documents/{id}/pages/001/final.json)
     * @param content    treść tekstowa (UTF-8)
     * @throws MinioStorageException gdy upload się nie powiedzie
     */
    public void uploadJson(String storageKey, String content) {
        try (InputStream is = new java.io.ByteArrayInputStream(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(is, content.length(), -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            throw new MinioStorageException(
                    "Błąd uploadu JSON do MinIO: " + storageKey + " — " + e.getMessage(), e);
        }
    }
}
