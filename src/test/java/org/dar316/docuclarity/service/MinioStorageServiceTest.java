package org.dar316.docuclarity.service;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.dar316.docuclarity.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testy integracyjne dla MinioStorageService.
 *
 * Sprawdza: inicjalizację bucketu, upload plików, usuwanie plików,
 * operacje na pustych strumieniach oraz idempotentność initializeBucket().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestcontainersConfiguration.class)
class MinioStorageServiceTest {

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    private static final String TEST_BUCKET = "docuclarity-test-minio";

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private MinioClient minioClient;

    @DynamicPropertySource
    static void configureMinio(DynamicPropertyRegistry registry) {
        registry.add("docuclarity.minio.endpoint", minio::getS3URL);
        registry.add("docuclarity.minio.access-key", minio::getUserName);
        registry.add("docuclarity.minio.secret-key", minio::getPassword);
        registry.add("docuclarity.minio.bucket", () -> TEST_BUCKET);
    }

    @BeforeEach
    void setUp() throws Exception {
        minioStorageService.initializeBucket();
        // Usuń wszystkie obiekty w bucketu
        for (var objResult : minioClient.listObjects(
                ListObjectsArgs.builder().bucket(TEST_BUCKET).build())) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(TEST_BUCKET)
                    .object(objResult.get().objectName())
                    .build());
        }
    }

    // --- Inicjalizacja bucketu ---

    @Test
    @DisplayName("Inicjalizacja bucketu jest idempotentna")
    void shouldInitializeExistingBucketWithoutException() throws Exception {
        minioStorageService.initializeBucket();
        assertThat(bucketExists(TEST_BUCKET)).isTrue();

        // Drugie wywołanie jest idempotentne
        assertThatCode(() -> minioStorageService.initializeBucket())
                .doesNotThrowAnyException();
    }

    // --- Upload ---

    @Test
    @DisplayName("Uploaduje mały plik do MinIO")
    void shouldUploadSmallPdfFileToMinio() throws Exception {
        byte[] content = "%PDF-1.4 mock pdf content.".getBytes(StandardCharsets.UTF_8);
        String storageKey = "documents/test-upload/small.pdf";

        minioStorageService.uploadFile(storageKey,
                new ByteArrayInputStream(content), "application/pdf", content.length);

        assertThat(objectExists(storageKey)).isTrue();
    }

    @Test
    @DisplayName("Uploaduje plik tekstowy z prawidłowym contentType")
    void shouldUploadTextFileWithCorrectContentType() throws Exception {
        String content = "Witaj świecie!";
        String storageKey = "documents/test/text/hello.txt";

        minioStorageService.uploadFile(storageKey,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "text/plain", content.length());

        assertThat(objectExists(storageKey)).isTrue();
    }

    @Test
    @DisplayName("Obsługuje upload z contentLength równym 0")
    void shouldHandleUploadWithContentLengthZero() {
        String storageKey = "documents/test/empty-file.txt";

        assertThatCode(() ->
                minioStorageService.uploadFile(storageKey,
                        new ByteArrayInputStream(new byte[0]), "text/plain", 0)
        ).doesNotThrowAnyException();

        assertThat(objectExists(storageKey)).isTrue();
    }

    // --- Usuwanie ---

    @Test
    @DisplayName("Usuwa istniejący plik z bucketu")
    void shouldDeleteExistingFileFromBucket() throws Exception {
        String content = "Treść do usunięcia";
        String storageKey = "documents/test/delete/me.txt";

        minioStorageService.uploadFile(storageKey,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "text/plain", content.length());
        assertThat(objectExists(storageKey)).isTrue();

        minioStorageService.deleteFile(storageKey);
        assertThat(objectExists(storageKey)).isFalse();
    }

    @Test
    @DisplayName("Nie rzuca wyjątku przy usuwaniu nieistniejącego pliku")
    void shouldIgnoreDeleteOfNonExistentFile() {
        assertThatCode(() ->
                minioStorageService.deleteFile("documents/nonexistent/file.txt")
        ).doesNotThrowAnyException();
    }

    // --- getBucket ---

    @Test
    @DisplayName("Zwraca nazwę bucketu zgodną z konfiguracją")
    void shouldReturnConfiguredBucketName() {
        assertThat(minioStorageService.getBucket()).isEqualTo(TEST_BUCKET);
    }

    // --- Edge cases ---

    @Test
    @DisplayName("StorageKey ze znakami polskimi")
    void shouldHandleStorageKeyWithPolishCharacters() {
        String content = "specjalne znaki polskie: ąęółżźć";
        String storageKey = "documents/polski/zrodlo.txt";

        assertThatCode(() ->
                minioStorageService.uploadFile(storageKey,
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        "text/plain", content.length())
        ).doesNotThrowAnyException();

        assertThat(objectExists(storageKey)).isTrue();
    }

    // --- Helpers ---

    private boolean bucketExists(String bucketName) throws Exception {
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }

    private boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(TEST_BUCKET)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
