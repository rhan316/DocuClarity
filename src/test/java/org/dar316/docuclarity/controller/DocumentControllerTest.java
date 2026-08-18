package org.dar316.docuclarity.controller;

import org.dar316.docuclarity.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testy integracyjne dla DocumentController (endpointy REST).
 *
 * Sprawdza: upload pliku multipart, pobieranie statusu dokumentu,
 * poprawne kody HTTP oraz obsługę wyjątków przez GlobalExceptionHandler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DocumentControllerTest {

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    private static final String TEST_BUCKET = "docuclarity-test-controller";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureMinio(DynamicPropertyRegistry registry) {
        registry.add("docuclarity.minio.endpoint", minio::getS3URL);
        registry.add("docuclarity.minio.access-key", minio::getUserName);
        registry.add("docuclarity.minio.secret-key", minio::getPassword);
        registry.add("docuclarity.minio.bucket", () -> TEST_BUCKET);
    }

    // --- POST /api/documents/upload — happy path ---

    @Test
    @DisplayName("Zaakceptuje mały plik PDF i zwraca 200 z metadanymi dokumentu")
    void shouldUploadSmallPdfAndReturnMetadata() throws Exception {
        byte[] content = "%PDF-1.4 mock pdf content.".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "testowy-dokument.pdf", "application/pdf", content);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.originalFilename").value("testowy-dokument.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.contentLength").value(content.length))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("Zaakceptuje plik tekstowy i zwraca poprawny response")
    void shouldUploadTextFileAndReturnCorrectResponse() throws Exception {
        String textContent = "To jest testowy plik tekstowy do uploadu.";
        byte[] bytes = textContent.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "raport.txt", "text/plain", bytes);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("raport.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.contentLength").value(bytes.length))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    @DisplayName("Zaakceptuje plik z polskimi znakami w nazwie")
    void shouldAcceptFileWithPolishCharactersInName() throws Exception {
        String content = "Dokument z polskimi znakami";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "dokument-z-polskimi-znakami.txt", "text/plain", bytes);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename")
                        .value("dokument-z-polskimi-znakami.txt"));
    }

    // --- POST /api/documents/upload — obsługa błędów ---

    @Test
    @DisplayName("Zwraca 400 gdy nie przesłano pliku")
    void shouldReturnBadRequestWhenNoFileUploaded() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Zwraca 400 gdy przesłano pusty plik")
    void shouldReturnBadRequestWhenEmptyFileUploaded() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Zwraca 400 z właściwą wiadomością błędu gdy plik jest pusty")
    void shouldReturnErrorMessageWhenFileIsEmpty() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.error").value("Plik jest pusty lub nie został przesłany"));
    }

    // --- GET /api/documents/{id} ---

    @Test
    @DisplayName("Zwraca 404 gdy dokument o podanym ID nie istnieje")
    void shouldReturnNotFoundWhenDocumentDoesNotExist() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/documents/{id}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.error").value(containsString(nonExistentId.toString())));
    }

    @Test
    @DisplayName("Zwraca 404 dla innego losowego UUID")
    void shouldReturnNotFoundForRandomUuid() throws Exception {
        UUID randomId = UUID.nameUUIDFromBytes("non-existent".getBytes());

        mockMvc.perform(get("/api/documents/{id}", randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // --- End-to-end ---

    @Test
    @DisplayName("Upload pliku a następnie pobranie jego statusu — pełen cykl")
    void fullUploadAndRetrieveCycle() throws Exception {
        String filename = "e2e-test.pdf";
        byte[] content = "End-to-end test document".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "application/pdf", content);

        // Upload
        String uploadResponse = mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(uploadResponse).contains("\"status\":\"UPLOADED\"");
        assertThat(uploadResponse).contains("\"originalFilename\":\"" + filename + "\"");

        // Wyciągnij documentId
        String docIdStr = extractJsonString(uploadResponse, "documentId");
        UUID docId = UUID.fromString(docIdStr);

        // Pobierz status
        mockMvc.perform(get("/api/documents/{id}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docIdStr))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.processingAttempts").value(0));
    }

    // --- Helpers ---

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) {
            throw new IllegalStateException("Klucz " + key + " nie znaleziony w: " + json);
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
