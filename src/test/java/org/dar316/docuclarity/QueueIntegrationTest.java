package org.dar316.docuclarity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.OutboxEntry;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.repository.OutboxRepository;
import org.dar316.docuclarity.service.DocumentService;
import org.dar316.docuclarity.service.MinioStorageService;
import org.dar316.docuclarity.service.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test integracyjny — pełny przepływ od uploadu przez outbox do Redis Streams.
 *
 * <p>Wzorzec z {@link DocuClarityApplicationTests}:
 * {@code @SpringBootTest} + {@code @Testcontainers} + {@code @Import(TestcontainersConfiguration)}.
 * PostgreSQL i Redis startują automatycznie przez {@code ServiceConnection};
 * MinIO dodane ręcznie jako {@code @Container}.
 *
 * <p>Scenariusz:</p>
 * <ol>
 *   <li>Generowanie prostego PDF za pomocą PDFBox.</li>
 *   <li>Upload przez {@link DocumentService} → dokument w DB + wpis outbox PENDING.</li>
 *   <li>Ręczne wywołanie {@link OutboxPublisher#publishPending()} → XADD na Redis Stream.</li>
 *   <li>Weryfikacja: outbox.status = PUBLISHED, strumień Redis ma 1 rekord.</li>
 * </ol>
 *
 * <p>NIE testuje pełnego consume przez StreamConsumer (to flaky przez timing).</p>
 */
@Testcontainers
@SpringBootTest(properties = {
        // Długi interwał publikacji — scheduler nie zakłóca testów
        "docuclarity.queue.publish-interval-ms=60000",
        // Próg maksymalnej liczby prób = max int, by worker nie zmieniał statusu dokumentu
        "docuclarity.queue.max-processing-attempts=2147483647"
})
@Import(TestcontainersConfiguration.class)
class QueueIntegrationTest {

    // =========================================================================
    // Kontenery testowe
    // =========================================================================

    @Container
    static MinIOContainer minioContainer = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    // =========================================================================
    // Dynamiczne właściwości
    // =========================================================================

    @DynamicPropertySource
    static void configureMinio(DynamicPropertyRegistry registry) {
        registry.add("docuclarity.minio.endpoint", minioContainer::getS3URL);
        registry.add("docuclarity.minio.access-key", minioContainer::getUserName);
        registry.add("docuclarity.minio.secret-key", minioContainer::getPassword);
        registry.add("docuclarity.minio.bucket", () -> "docuclarity-test-" + UUID.randomUUID());
    }

    // =========================================================================
    // Wstrzyknięte zależności
    // =========================================================================

    @Autowired DocumentService documentService;

    @Autowired DocumentRepository documentRepository;

    @Autowired OutboxRepository outboxRepository;

    @Autowired MinioStorageService minioStorageService;

    @Autowired ObjectMapper objectMapper;

    @Autowired @Qualifier("queueRedisTemplate")
    RedisTemplate<String, String> queueRedisTemplate;

    @Autowired OutboxPublisher outboxPublisher;

    // =========================================================================
    // Pomocnicze metody
    // =========================================================================

    /**
     * Generuje prosty jednonastrowy plik PDF z tekstem przy użyciu Apache PDFBox 3.x.
     */
    private byte[] generateSimplePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    // =========================================================================
    // Test
    // =========================================================================

    @Test
    @DisplayName("Pełny round-trip: upload → outbox PENDING → publish → Redis stream")
    void upload_publishVerifyOutboxAndStream_shouldSucceed() throws Exception {
        // --- Krok 1: generowanie PDF i upload przez DocumentService ---
        byte[] pdfBytes = generateSimplePdf(
                "DocuClarity Integration Test — Simple one-page PDF document with readable text.");
        ByteArrayInputStream pdfStream = new ByteArrayInputStream(pdfBytes);
        long contentLength = pdfBytes.length;

        Document uploadedDocument = documentService.uploadDocument(
                "integration-test.pdf",
                "application/pdf",
                contentLength,
                pdfStream
        );

        UUID documentId = uploadedDocument.getId();
        assertNotNull(documentId, "uploadDocument powinien zwrócić dokument z wygenerowanym ID");

        // --- Krok 2: weryfikacja wpisu outbox (status PENDING) ---
        List<OutboxEntry> pendingEntries = outboxRepository.findPending();
        assertFalse(pendingEntries.isEmpty(),
                "Po uploadie powinien być przynajmniej jeden wpis outbox o statusie PENDING");

        Optional<OutboxEntry> myEntry = pendingEntries.stream()
                .filter(e -> e.getDocumentId().equals(documentId))
                .findFirst();
        assertTrue(myEntry.isPresent(),
                "Wybrany wpis outbox powinien wskazywać na nasz dokument");
        assertEquals("PENDING", myEntry.get().getStatus(),
                "Nowy wpis outbox powinien mieć status PENDING");

        // --- Krok 3: ręczne uruchomienie publishera ---
        outboxPublisher.publishPending();

        // --- Krok 4: weryfikacja outbox po publikacji ---
        Optional<OutboxEntry> publishedEntry = outboxRepository.findById(myEntry.get().getId());
        assertTrue(publishedEntry.isPresent());
        assertEquals("PUBLISHED", publishedEntry.get().getStatus(),
                "Po publishPending() outbox status powinien stać się PUBLISHED");
        assertNotNull(publishedEntry.get().getPublishedAt(),
                "publishedAt powinno zostać ustawione na Instant.now()");

        // --- Krok 5: weryfikacja obecności rekordu w Redis Stream ---
        String streamKey = "docuclarity.documents";

        // Czy klucz istnieje?
        Boolean keyExists = queueRedisTemplate.hasKey(streamKey);
        assertTrue(keyExists, "Klucz Redis Stream '" + streamKey + "' powinien istnieć po publikacji");

        // Rozmiar strumienia (liczba wszystkich rekordów)
        Long streamSize = queueRedisTemplate.opsForStream().size(streamKey);
        assertNotNull(streamSize, "size() z Redis Stream nie może zwracać null");
        assertTrue(streamSize >= 1,
                "Strumień Redis powinien zawierać co najmniej 1 rekord (opublikowany outbox entry), size=" + streamSize);

        // Sprawdź konkretną zawartość pierwszego rekordu
        List<MapRecord<String, Object, Object>> entries = queueRedisTemplate.opsForStream()
                .range(streamKey, org.springframework.data.domain.Range.unbounded());
        assertNotNull(entries);
        assertFalse(entries.isEmpty(), "Strumień powinien mieć przynajmniej jeden rekord odczytywalny");

        MapRecord<String, Object, Object> firstEntry = entries.get(0);
        assertTrue(firstEntry.getValue().containsKey("documentId"),
                "Rekord stream powinien zawierać field 'documentId'");
        assertEquals(documentId.toString(), String.valueOf(firstEntry.getValue().get("documentId")),
                "documentId w streamie powinien zgadzać się z opublikowanym dokumentem");

        assertTrue(firstEntry.getValue().containsKey("eventType"),
                "Rekord stream powinien zawierać field 'eventType'");
        assertTrue(String.valueOf(firstEntry.getValue().get("eventType")).contains("DOCUMENT_UPLOADED"),
                "eventType powinien to DOCUMENT_UPLOADED");

        assertTrue(firstEntry.getValue().containsKey("payload"),
                "Rekord stream powinien zawierać field 'payload'");
        assertTrue(String.valueOf(firstEntry.getValue().get("payload")).contains(documentId.toString()),
                "Payload JSON powinien zawierać documentId");
    }

    @Test
    @DisplayName("Podwójna publikacja jest idempotentna — drugi publish nie tworzy duplikatów")
    void doublePublish_shouldNotDuplicateInStream() throws Exception {
        // --- Krok 1: upload dokumentu ---
        byte[] pdfBytes = generateSimplePdf("Second test: double-publish idempotency check.");
        Document uploadedDocument = documentService.uploadDocument(
                "double-publish-test.pdf",
                "application/pdf",
                pdfBytes.length,
                new ByteArrayInputStream(pdfBytes)
        );
        UUID documentId = uploadedDocument.getId();

        // --- Krok 2: pierwszy publish ---
        outboxPublisher.publishPending();

        // Odczekaj krótko by upewnić się że transakcja DB zdążyła się zamknąć
        Thread.sleep(100);

        String streamKey = "docuclarity.documents";
        Long sizeAfterFirst = queueRedisTemplate.opsForStream().size(streamKey);

        // --- Krok 3: drugi publish (ten sam wpis już PUBLISHED) ---
        outboxPublisher.publishPending();

        // --- Krok 4: rozmiar nie zmienił się ---
        Long sizeAfterSecond = queueRedisTemplate.opsForStream().size(streamKey);
        assertEquals(sizeAfterFirst, sizeAfterSecond,
                "Podwójna publikacja nie powinna dodać duplikatu do Redis Stream");

        // Wpis outbox nadal PUBLISHED
        List<OutboxEntry> pendingAfterDouble = outboxRepository.findPending().stream()
                .filter(e -> e.getDocumentId().equals(documentId))
                .toList();
        assertTrue(pendingAfterDouble.isEmpty(),
                "Po dwukrotnej publikacji nie powinno być już wpisów PENDING dla tego dokumentu");
    }
}
