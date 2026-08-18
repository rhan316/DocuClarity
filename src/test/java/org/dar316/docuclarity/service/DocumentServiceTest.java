package org.dar316.docuclarity.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.dar316.docuclarity.TestcontainersConfiguration;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.OutboxEntry;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.repository.OutboxRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy integracyjne dla DocumentService.
 *
 * Sprawdza: pełny przepływ upload dokumentu (MinIO + DB + outbox),
 * walidację wejścia, wyszukiwanie dokumentów oraz końcowy stan po uploadzie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DocumentServiceTest {

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    private static final String TEST_BUCKET = "docuclarity-test-document";

    @Autowired
    private DocumentService documentService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private MinioStorageService minioStorageService;

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

        // Wyczyść obiekty w bucketu
        for (var objResult : minioClient.listObjects(
                ListObjectsArgs.builder().bucket(TEST_BUCKET).build())) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(TEST_BUCKET)
                    .object(objResult.get().objectName())
                    .build());
        }

        // Wyczyść DB — najpierw outbox (FK), potem documents
        for (OutboxEntry entry : collectAll(outboxRepository)) {
            outboxRepository.delete(entry);
        }
        for (Document doc : collectAll(documentRepository)) {
            documentRepository.delete(doc);
        }
    }

    // --- Upload — happy path ---

    @Test
    @DisplayName("Upload PDF z prawidłowymi parametrami")
    void shouldUploadPdfSuccessfully() {
        String filename = "testowy-dokument.pdf";
        String contentType = "application/pdf";
        byte[] content = "%PDF-1.4 mock pdf content.".getBytes(StandardCharsets.UTF_8);

        Document document = documentService.uploadDocument(
                filename, contentType, content.length,
                new ByteArrayInputStream(content));

        assertThat(document.getId()).isNotNull();
        assertThat(document.getOriginalFilename()).isEqualTo(filename);
        assertThat(document.getContentType()).isEqualTo(contentType);
        assertThat(document.getContentLength()).isEqualTo(content.length);
        assertThat(document.getStatus()).isEqualTo("UPLOADED");
        assertThat(document.getProcessingAttempts()).isZero();
        assertThat(document.getCreatedAt()).isNotNull();

        // Plik istnieje w MinIO
        assertThat(document.getStorageKey()).startsWith("documents/").endsWith("/source");
        assertObjectExistsInMinio(document.getStorageKey());

        // Rekord w DB
        Optional<Document> saved = documentRepository.findById(document.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo("UPLOADED");

        // Wpis outbox PENDING
        List<OutboxEntry> pending = outboxRepository.findPending();
        assertThat(pending).hasSize(1);
        OutboxEntry entry = pending.get(0);
        assertThat(entry.getDocumentId()).isEqualTo(document.getId());
        assertThat(entry.getEventType()).isEqualTo("DOCUMENT_UPLOADED");
        assertThat(entry.getStatus()).isEqualTo("PENDING");
        assertThat(entry.getPayload()).contains("\"documentId\"");
        assertThat(entry.getPayload()).contains(document.getId().toString());
    }

    @Test
    @DisplayName("Upload pliku tekstowego")
    void shouldUploadTextFileSuccessfully() {
        String content = "To jest testowa treść dokumentu tekstowego.";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        Document document = documentService.uploadDocument(
                "raport.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));

        assertThat(document.getId()).isNotNull();
        assertThat(document.getOriginalFilename()).isEqualTo("raport.txt");
        assertObjectExistsInMinio(document.getStorageKey());
        assertThat(outboxRepository.findPending()).hasSize(1);
    }

    @Test
    @DisplayName("Zwraca unikalne identyfikatory dla każdego dokumentu")
    void shouldReturnUniqueIdsForEachDocument() {
        byte[] content = "treść".getBytes(StandardCharsets.UTF_8);

        Document doc1 = documentService.uploadDocument(
                "dokument1.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));
        Document doc2 = documentService.uploadDocument(
                "dokument2.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));

        assertThat(doc1.getId()).isNotEqualTo(doc2.getId());
        assertThat(doc1.getStorageKey()).isNotEqualTo(doc2.getStorageKey());
        assertThat(documentRepository.findById(doc1.getId())).isPresent();
        assertThat(documentRepository.findById(doc2.getId())).isPresent();
    }

    // --- Walidacja wejścia ---

    @Test
    @DisplayName("Rzuca wyjątek gdy nazwa pliku jest pusta")
    void shouldThrowWhenFilenameIsEmpty() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("", "application/pdf", 12L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Nazwa pliku nie może być pusta");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy nazwa pliku to same spacje")
    void shouldThrowWhenFilenameIsBlank() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("   ", "application/pdf", 12L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Nazwa pliku nie może być pusta");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy nazwa pliku jest null")
    void shouldThrowWhenFilenameIsNull() {
        assertThatThrownBy(() ->
                documentService.uploadDocument(null, "application/pdf", 12L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Nazwa pliku nie może być pusta");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy contentType jest null")
    void shouldThrowWhenContentTypeIsNull() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("plik.pdf", null, 12L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Typ zawartości nie może być pusty");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy contentType jest pusty")
    void shouldThrowWhenContentTypeIsEmpty() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("plik.pdf", "", 12L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Typ zawartości nie może być pusty");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy contentLength wynosi zero")
    void shouldThrowWhenContentLengthIsZero() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("plik.pdf", "application/pdf", 0L,
                        new ByteArrayInputStream(new byte[0]))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Długość pliku musi być dodatnia");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy contentLength jest ujemne")
    void shouldThrowWhenContentLengthIsNegative() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("plik.pdf", "application/pdf", -5L,
                        new ByteArrayInputStream("treść".getBytes()))
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Długość pliku musi być dodatnia");
    }

    @Test
    @DisplayName("Rzuca wyjątek gdy inputStream jest null")
    void shouldThrowWhenInputStreamIsNull() {
        assertThatThrownBy(() ->
                documentService.uploadDocument("plik.pdf", "application/pdf", 12L, null)
        ).isInstanceOf(DocumentUploadException.class)
         .hasMessageContaining("Strumień wejściowy nie może być null");
    }

    // --- Pobieranie dokumentu ---

    @Test
    @DisplayName("Znajduje dokument po poprawnym ID")
    void shouldFindDocumentById() {
        byte[] content = "Treść do wyszukania".getBytes(StandardCharsets.UTF_8);
        Document uploaded = documentService.uploadDocument(
                "szukany.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));

        Document found = documentService.getDocument(uploaded.getId());

        assertThat(found.getId()).isEqualTo(uploaded.getId());
        assertThat(found.getOriginalFilename()).isEqualTo("szukany.pdf");
        assertThat(found.getStatus()).isEqualTo("UPLOADED");
    }

    @Test
    @DisplayName("Rzuca DocumentNotFoundException gdy dokument nie istnieje")
    void shouldThrowWhenDocumentNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        assertThatThrownBy(() -> documentService.getDocument(nonExistentId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());
    }

    // --- Pełny stan po uploadzie ---

    @Test
    @DisplayName("Po uploadzie dokument jest w DB, outbox i MinIO")
    void verifyFullStateAfterUpload() {
        String content = "Pełna weryfikacja stanu po uploadzie.";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        Document document = documentService.uploadDocument(
                "full-check.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));

        // Dokument w DB
        Optional<Document> dbDoc = documentRepository.findById(document.getId());
        assertThat(dbDoc).isPresent();
        assertThat(dbDoc.get().getStatus()).isEqualTo("UPLOADED");

        // Outbox PENDING
        List<OutboxEntry> outboxEntries = outboxRepository.findPending();
        assertThat(outboxEntries).hasSize(1);
        assertThat(outboxEntries.get(0).getDocumentId()).isEqualTo(document.getId());
        assertThat(outboxEntries.get(0).getEventType()).isEqualTo("DOCUMENT_UPLOADED");

        // Plik w MinIO
        assertObjectExistsInMinio(document.getStorageKey());

        // Payload JSON poprawny
        String payload = outboxEntries.get(0).getPayload();
        assertThat(payload).contains("documentId");
        assertThat(payload).contains(document.getId().toString());
        assertThat(payload).contains("storageKey");
    }

    // --- Kompensacja ---

    @Test
    @DisplayName("deleteFile jest odporna na brak pliku (kompensacja)")
    void compensationCleanupIgnoresMissingFiles() {
        assertThatCode(() ->
                minioStorageService.deleteFile("documents/compensation/nonexistent.pdf")
        ).doesNotThrowAnyException();
    }

    // --- Helpers ---

    private <T, ID> List<T> collectAll(
            org.springframework.data.repository.CrudRepository<T, ID> repo) {
        List<T> result = new ArrayList<>();
        repo.findAll().forEach(result::add);
        return result;
    }

    private void assertObjectExistsInMinio(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(TEST_BUCKET)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new AssertionError("Oczekiwano obiektu w MinIO: " + objectKey, e);
        }
    }
}
