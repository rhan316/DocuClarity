package org.dar316.docuclarity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.ExtractedPageResult;
import org.dar316.docuclarity.dto.OcrPageResult;
import org.dar316.docuclarity.dto.PageQualityScore;
import org.dar316.docuclarity.dto.PdfPageText;
import org.dar316.docuclarity.dto.PdfTextExtractionResult;
import org.dar316.docuclarity.dto.RoutingDecision;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe {@link DocumentProcessingService}.
 *
 * <p>Używają Mockito bez Springa i bez Testcontainers. Realne instancje:
 * {@code new PageQualityEvaluator()} oraz {@code new ObjectMapper()}.
 * Wszystkie serwisy (MinIO, PDFBox, OCR, repozytorium, transakcje) — mockowane.</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    // =========================================================================
    // Zależności — mocki tworzone ręcznie w setUp() (MockitoExtension nie
    // inicjalizuje @Mock w obecnej konfiguracji z nested testami).
    // =========================================================================
    MinioStorageService minioStorageService;
    PdfTextExtractionService pdfTextExtractionService;
    Tess4jOcrService tess4jOcrService;
    DocumentRepository documentRepository;
    DocumentProgressService documentProgressService;
    TransactionTemplate transactionTemplate;
    /**QualityEvaluator zmockowany — testy wymuszają decyzję routingu. */
    PageQualityEvaluator pageQualityEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private DocumentProcessingService sut;

    // ------------------------------------------------------------------
    // Setup wspólny dla każdego teścia
    // ------------------------------------------------------------------
    @BeforeEach
    void setUp() {
        minioStorageService = Mockito.mock(MinioStorageService.class);
        pdfTextExtractionService = Mockito.mock(PdfTextExtractionService.class);
        tess4jOcrService = Mockito.mock(Tess4jOcrService.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        documentProgressService = Mockito.mock(DocumentProgressService.class);
        transactionTemplate = Mockito.mock(TransactionTemplate.class);
        pageQualityEvaluator = Mockito.mock(PageQualityEvaluator.class);

        sut = new DocumentProcessingService(
                minioStorageService,
                pdfTextExtractionService,
                pageQualityEvaluator,
                tess4jOcrService,
                documentRepository,
                documentProgressService,
                objectMapper,
                transactionTemplate,
                3
        );
    }

    // ============================================================
    // Pomocnicze metody konfiguracyjne
    // ============================================================

    /**
     * Konfiguruje TransactionTemplate tak, by obie wersje wywołań wykonać
     * synchronicznie: execute() dla TransactionCallback oraz
     * executeWithoutResult() dla Consumer<TransactionStatus>.
     */
    private void initTxMocks() {
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> invokeCallback(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            invokeConsumer(inv.getArgument(0));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any(Consumer.class));
    }

    /** Wykonuje TransactionCallback.doInTransaction() bez refleksji. */
    private Object invokeCallback(TransactionCallback<?> cb) {
        return cb.doInTransaction(null);
    }

    /** Wykonuje Consumer<TransactionStatus> — zastępuje deprecated TransactionCallbackWithoutResult. */
    @SuppressWarnings("unchecked")
    private void invokeConsumer(Consumer<TransactionStatus> consumer) {
        try {
            consumer.accept(null);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się wykonać lambdy transakcji bez wyniku", e);
        }
    }

    /**
     * Konfiguruje repository i dokument na potrzeby testu.
     * Zwraca referencję do mutowalnego obiektu Document — zmiana stanu przez jedną lambdę
     * jest widoczna przy kolejnych wywołaniach findById().
     */
    private Document prepareDocument(UUID id, String storageKey, int initialAttempts) {
        Document doc = new Document("test.pdf", "application/pdf", 2048L, storageKey);
        doc.setId(id);
        doc.setProcessingAttempts(initialAttempts);
        doc.setStatus(DocumentStatus.UPLOADED.code());

        lenient().when(documentRepository.findById(eq(id))).thenReturn(Optional.of(doc));
        lenient().when(documentRepository.save(any(Document.class))).thenAnswer(i -> i.getArgument(0));
        return doc;
    }

    // ============================================================
    // Test 1 — PDF z tekstem → COMPLETED, brak OCR
    // ============================================================

    @Nested
    @DisplayName("PDF z dobrą jakością tekstu — routing PDFBOX → COMPLETED")
    class PdfGoodTextSuccessTest {

        @Test
        @DisplayName("process() dla PDF z wystarczającym tekstem ustawia status COMPLETED, nie wywołuje OCR")
        void process_pdfWithGoodText_shouldCompleteSuccessfully() throws Exception {
            // given
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 0);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);

            PdfPageText page1 = new PdfPageText(1, "Tekst przykładowy dokumentu integracyjnego.", 38, 5, true);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenReturn(new PdfTextExtractionResult(1, List.of(page1), "Tekst przykładowy..."));

            /* Zmuszamy evaluator do decyzji PDFBOX (score >= 0.85) */
            when(pageQualityEvaluator.evaluate(page1))
                    .thenReturn(new PageQualityScore(
                            1, true, 38, 5, 0, 0.90, 6.2, 0.95, List.of(), RoutingDecision.PDFBOX));

            initTxMocks();

            // when
            sut.process(docId);

            // then
            ArgumentCaptor<Document> saveCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(saveCaptor.capture());
            List<Document> saved = saveCaptor.getAllValues();
            assertEquals("COMPLETED", saved.get(saved.size() - 1).getStatus());

            // Minerka: uploadJson — per strona + podsumowanie
            verify(minioStorageService, times(2)).uploadJson(anyString(), anyString());

            // Bez wywołań OCR
            verify(tess4jOcrService, never()).ocrPage(any(byte[].class), anyInt());

            // ErrorMessage powinien zostać usunięty (null)
            assertNull(saved.get(saved.size() - 1).getErrorMessage());
        }
    }

    // ============================================================
    // Test 2 — OCR_REQUIRED → tess4j wykonany → COMPLETED
    // ============================================================

    @Nested
    @DisplayName("Strona wymaga OCR — routing OCR_REQUIRED → COMPLETED po rozpoznanium")
    class OcrRequiredSuccessTest {

        @Test
        @DisplayName("process() gdy evaluatorem decyduje OCR_REQUIRED, OCR daje tekst → COMPLETED")
        void process_ocrRequiredAndSuccessful_shouldCompleteViaOcr() throws Exception {
            // given
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 0);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);

            PdfPageText page1 = new PdfPageText(1, "Słaba jakość — niewiele słów.\n\n\n\n", 26, 2, true);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenReturn(new PdfTextExtractionResult(1, List.of(page1), "Słaba jakość..."));

            /* Evaluator zwraca OCR_REQUIRED (score niski bo za mało słów, alphaRatio ≈ 1 ale wordCountFactor niski) */
            when(pageQualityEvaluator.evaluate(page1))
                    .thenReturn(new PageQualityScore(
                            1, true, 26, 2, 0, 0.85, 4.5, 0.43, List.of("Za mało słów (2 < 5)"), RoutingDecision.OCR_REQUIRED));

            OcrPageResult ocrResult = new OcrPageResult(1, "Rozpoznany tekst OCR z dobrej jakości.",
                    List.of(), 88, true);
            when(tess4jOcrService.ocrPage(dummyPdf, 0)).thenReturn(ocrResult);

            initTxMocks();

            // when
            sut.process(docId);

            // then
            verify(tess4jOcrService, times(1)).ocrPage(eq(dummyPdf), eq(0));

            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(captor.capture());
            Document lastSaved = captor.getValue();
            assertEquals("COMPLETED", lastSaved.getStatus());

            // Sprawdź że wynik strony ma engine="OCR_TESS4J" i tekst z OCR
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(minioStorageService, times(2)).uploadJson(anyString(), jsonCaptor.capture());
            String pageJson = jsonCaptor.getAllValues().get(0); // first = page, second = summary
            assertTrue(pageJson.contains("\"OCR_TESS4J\""));
            assertTrue(pageJson.contains("Rozpoznany tekst OCR z dobrej jakości."));
        }
    }

    // ============================================================
    // Test 3 — OCR_required ale OCR nie dał tekstu → MANUAL_REVIEW
    // ============================================================

    @Nested
    @DisplayName("OCR_required, ale OCR nie rozpoznał tekstu → MANUAL_REVIEW")
    class OcrRequiredNoTextTest {

        @Test
        @DisplayName("process() gdy OCR_required i textPresent=false → MANUAL_REVIEW")
        void process_ocrRequiredButNoText_shouldGoToManualReview() throws Exception {
            // given
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 0);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);

            PdfPageText page1 = new PdfPageText(1, "   ", 3, 0, true);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenReturn(new PdfTextExtractionResult(1, List.of(page1), ""));

            when(pageQualityEvaluator.evaluate(page1))
                    .thenReturn(new PageQualityScore(
                            1, true, 3, 0, 0, 0.0, 0.0, 0.0,
                            List.of("Za mało słów (0 < 5)"), RoutingDecision.OCR_REQUIRED));

            // OCR zwraca textPresent=false (nie rozpoznało żadnego tekstu)
            OcrPageResult ocrBad = new OcrPageResult(1, "", List.of(), 10, false);
            when(tess4jOcrService.ocrPage(dummyPdf, 0)).thenReturn(ocrBad);

            initTxMocks();

            // when
            sut.process(docId);

            // then
            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(captor.capture());
            assertEquals("MANUAL_REVIEW", captor.getValue().getStatus());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(minioStorageService, times(2)).uploadJson(anyString(), jsonCaptor.capture());
            String pageJson = jsonCaptor.getAllValues().get(0);
            assertTrue(pageJson.contains("\"OCR_TESS4J\""));
            assertTrue(pageJson.contains("\"text\":\"\"")); // pusty tekst — OCR nic nie rozpoznał
            // warning zawiera informację o braku rozpoznania
            assertTrue(jsonCaptor.getAllValues().get(1).contains("MANUAL_REVIEW"));
        }
    }

    // ============================================================
    // Test 4 — Dokument nie istnieje
    // ============================================================

    @Nested
    @DisplayName("Dokument nie istnieje w repository → DocumentProcessingException")
    class DocumentNotFoundTest {

        @Test
        @DisplayName("process() z nieistniejącym ID rzuca DocumentProcessingException")
        void process_documentDoesNotExist_shouldThrowDocumentProcessingException() {
            // given
            UUID nonExistentId = UUID.randomUUID();
            when(documentRepository.findById(nonExistentId)).thenReturn(Optional.empty());
            initTxMocks();

            // when / then
            assertThrows(DocumentProcessingException.class, () -> sut.process(nonExistentId));
        }
    }

    // ============================================================
    // Test 5 — Idempotność: już COMPLETED
    // ============================================================

    @Nested
    @DisplayName("Idempotność — dokument już COMPLETED zostaje bez zmian")
    class IdempotentCompletedTest {

        @Test
        @DisplayName("process() na dokumencie COMPLETED pomija przetwarzanie, nic nie robi")
        void process_alreadyCompleted_shouldSkipQuietly() throws Exception {
            // given
            UUID docId = UUID.randomUUID();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 0);
            doc.setStatus(DocumentStatus.COMPLETED.code());

            initTxMocks();

            // when
            sut.process(docId);

            // then — extractText NIE był wywołany (worker wychodzi przed doProcess)
            verify(pdfTextExtractionService, never()).extractText(any(byte[].class));

            // Brak dodatkowych zapisów — jedyny save to ten z pierwszego execute (który
            // w tym przypadku nie wywoła save(), tylko zwraca null z if)
            verify(documentRepository, never()).save(any());
            verify(minioStorageService, never()).downloadFile(anyString());
        }
    }

    // ============================================================
    // Test 6 — Błąd OCR (wyjątek rzucany przez service) → MANUAL_REVIEW
    // ============================================================

    @Nested
    @DisplayName("Błąd OCR — wyjątek z tess4j obsłużony wewnętrznie, MANUAL_REVIEW")
    class OcrErrorExceptionTest {

        @Test
        @DisplayName("process() gdy OCR rzuca RuntimeException → MANUAL_REVIEW (nie propaguje)")
        void process_ocrRuntimeException_shouldMarkManualReview() throws Exception {
            // given — 2 strony: druga routing OCR_REQUIRED, OCR rzuca wyjątek
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 0);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);

            PdfPageText page1 = new PdfPageText(1, "Pierwsza strona dobry tekst.", 29, 4, true);
            PdfPageText page2 = new PdfPageText(2, "Druga strona wymaga poprawki...", 32, 5, true);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenReturn(new PdfTextExtractionResult(2, List.of(page1, page2), ""));

            // Strona 1 → PDFBOX, strona 2 → OCR_REQUIRED
            when(pageQualityEvaluator.evaluate(page1))
                    .thenReturn(new PageQualityScore(
                            1, true, 29, 4, 0, 0.9, 6.0, 0.87, List.of(), RoutingDecision.PDFBOX));
            when(pageQualityEvaluator.evaluate(page2))
                    .thenReturn(new PageQualityScore(
                            2, true, 32, 5, 0, 0.88, 5.5, 0.82,
                            List.of("Za mało słów (5 >= 5)", "Niska quality score"), RoutingDecision.OCR_REQUIRED));

            // OCR dla strony 2 rzuca wyjątek
            when(tess4jOcrService.ocrPage(dummyPdf, 1))
                    .thenThrow(new RuntimeException("Tesseract binary not found"));

            initTxMocks();

            // when — proces się nie kończy wyjątkiem (obsłużony wewnętrznie)
            assertDoesNotThrow(() -> sut.process(docId));

            // then
            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(captor.capture());
            assertEquals("MANUAL_REVIEW", captor.getValue().getStatus());

            // Sprawdzamy że JSON zawiera stronę z błędem OCR
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(minioStorageService, times(3)).uploadJson(anyString(), jsonCaptor.capture());
            List<String> allJsons = jsonCaptor.getAllValues();
            // Strona 2 (page key #1) i result.json (#2) zawierają błąd
            String page2Json = allJsons.get(1);
            assertTrue(page2Json.contains("OCR_TESS4J"));
            assertTrue(page2Json.contains("Błąd OCR"));
        }
    }

    // ============================================================
    // Test 7 — Błąd ekstrakcji PDF, retry możliwy → UPLOADED
    // ============================================================

    @Nested
    @DisplayName("Błąd ekstrakcji PDF (retry możliwy) → status wraca do UPLOADED")
    class ExtractFailureRetryTest {

        @Test
        @DisplayName("process() gdy pdfService rzuca exception i attempts < max → UPLOADED (retry)")
        void process_extractFailsBelowMaxRetry_shouldReturnToUploaded() throws Exception {
            // given — próba 1 (initial attempts=1), max=3; po inkrementacji w tx → 2 < 3
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 1);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenThrow(new RuntimeException("Uszkodzony plik PDF"));

            initTxMocks();

            // when
            assertDoesNotThrow(() -> sut.process(docId));

            // then
            ArgumentCaptor<Document> saveCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(saveCaptor.capture());
            Document lastSaved = saveCaptor.getValue();
            assertEquals("UPLOADED", lastSaved.getStatus(),
                    "Po błędzie poniżej limitu retry, dokument wraca do UPLOADED");
            assertNotNull(lastSaved.getErrorMessage(), "errorMessage powinien być ustawiony");
            assertTrue(lastSaved.getErrorMessage().contains("Uszkodzony plik PDF"));
            assertEquals(2, lastSaved.getProcessingAttempts(),
                    "processingAttempts po incementce w tx = 1+1 = 2");
        }
    }

    // ============================================================
    // Test 8 — Błąd ekstrakcji PDF, przekroczenie retry → FAILED
    // ============================================================

    @Nested
    @DisplayName("Błąd ekstrakcji PDF (przekroczenie limitu retry) → FAILED")
    class ExtractFailureExceededTest {

        @Test
        @DisplayName("process() gdy pdfService rzuca exception i attempts >= max → FAILED")
        void process_extractFailsAtMaxRetry_shouldFail() throws Exception {
            // given — próba 3 (initial attempts=3), max=3; po inkrementacji → 4 >= 3
            UUID docId = UUID.randomUUID();
            byte[] dummyPdf = "dummy-pdf-bytes".getBytes();
            String sourceKey = "documents/" + docId + "/source";
            Document doc = prepareDocument(docId, sourceKey, 3);
            ByteArrayInputStream dummyStream = new ByteArrayInputStream(dummyPdf);

            when(minioStorageService.downloadFile(sourceKey)).thenReturn(dummyStream);
            when(pdfTextExtractionService.extractText(dummyPdf))
                    .thenThrow(new RuntimeException("Nie można wczytać PDF — format nieznany"));

            initTxMocks();

            // when
            assertDoesNotThrow(() -> sut.process(docId));

            // then
            ArgumentCaptor<Document> saveCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(saveCaptor.capture());
            Document lastSaved = saveCaptor.getValue();
            assertEquals("FAILED", lastSaved.getStatus(),
                    "Po przekroczeniu limitu retry, dokument przechodzi na FAILED");
            assertNotNull(lastSaved.getErrorMessage());
            assertTrue(lastSaved.getErrorMessage().contains("Nie można wczytać PDF"));
            assertEquals(4, lastSaved.getProcessingAttempts(),
                    "processingAttempts po incementce w tx = 3+1 = 4");
        }
    }
}
