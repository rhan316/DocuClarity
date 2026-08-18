package org.dar316.docuclarity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.*;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.util.DocumentProcessingServiceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Serwis wykonujący właściwe przetwarzanie dokumentu (worker).
 *
 * <p>Przepływ per dokument:</p>
 * <ol>
 *   <li>Pobranie pliku źródłowego z MinIO.</li>
 *   <li>Ekstrakcja tekstu przez PDFBox (per strona).</li>
 *   <li>Ocena jakości per strona (PageQualityEvaluator) → decyzja routingu.</li>
 *   <li>Dla stron OCR_REQUIRED: OCR przez Tess4J (render + rozpoznanie).</li>
 *   <li>Zapis wyniku per strona w MinIO (documents/{id}/pages/{nnn}/final.json)
 *       oraz podsumowania (documents/{id}/result.json).</li>
 *   <li>Aktualizacja statusu dokumentu: COMPLETED (gdy wszystkie strony mają tekst)
 *       lub MANUAL_REVIEW (gdy którakolwiek strona nie dała użytecznego tekstu).</li>
 * </ol>
 *
 * <p>Logika jest odporna na self-invocation (jak DocumentService) — używa
 * TransactionTemplate zamiast @Transactional. CPU-bound OCR/Tesseract jest
 * wywoływane poza wątkiem webowym (konsument deleguje przez TaskExecutor).</p>
 */
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final MinioStorageService minioStorageService;
    private final PdfTextExtractionService pdfTextExtractionService;
    private final PageQualityEvaluator pageQualityEvaluator;
    private final Tess4jOcrService tess4jOcrService;
    private final DocumentRepository documentRepository;
    private final DocumentProgressService documentProgressService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int maxProcessingAttempts;

    public DocumentProcessingService(
            MinioStorageService minioStorageService,
            PdfTextExtractionService pdfTextExtractionService,
            PageQualityEvaluator pageQualityEvaluator,
            Tess4jOcrService tess4jOcrService,
            DocumentRepository documentRepository,
            DocumentProgressService documentProgressService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            int maxProcessingAttempts) {
        this.minioStorageService = minioStorageService;
        this.pdfTextExtractionService = pdfTextExtractionService;
        this.pageQualityEvaluator = pageQualityEvaluator;
        this.tess4jOcrService = tess4jOcrService;
        this.documentRepository = documentRepository;
        this.documentProgressService = documentProgressService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.maxProcessingAttempts = maxProcessingAttempts;
    }

    public static DocumentProcessingServiceBuilder builder() {
        return new DocumentProcessingServiceBuilder();
    }

    /**
     * Przetwarza dokument o podanym ID. Wywoływane przez konsumenta Redis Streams.
     *
     * @param documentId id dokumentu do przetworzenia
     */
    public void process(UUID documentId) {
        /*
        Phase 1:
            Atomic claim - only one thread can succeed.
            The UPDATE ... WHERE status = 'UPLOADED' is a single atomic
            operation: the row lock is acquired and the status is changed
            in the same statement. A concurrent transaction that evaluates
            the WHERE clause after this one commits will see 'PROCESSING'
            and match 0 rows.
         */
        Integer rowsAffected = transactionTemplate.execute(
                status -> documentRepository.claimForProcessing(documentId)
        );

        if (rowsAffected == null ||  rowsAffected == 0) {
            // Claim failed - distinguish "not found" from 'already in progress' / done"
            Document existing = documentRepository.findById(documentId).orElse(null);
            if (existing == null) {
                log.error("Document not found for document id {}", documentId);
                throw new DocumentProcessingException("Document not found: " + documentId);
            }
            log.info("Document {} have status {} - skipping", documentId, existing.getStatus());
            return;
        }

        /*
        Phase 2:
            Read the claimed document (status is now PROCESSING, committed)
         */
        Document document = documentRepository.findById(documentId).orElseThrow();
        documentProgressService.notifyProgress(DocumentProgressEvent.of(
                documentId,
                DocumentStatus.PROCESSING,
                "STARTED",
                null,
                null,
                "Processing started"
        ));

        /*
        Phase 3:
            Process
         */
        try {
            doProcess(document);
        } catch (Exception e) {
            handleFailure(document, e);
        }
    }

    private void doProcess(Document document) throws Exception {
        UUID documentId = document.getId();
        String sourceKey = document.getStorageKey();
        documentProgressService.notifyProgress(DocumentProgressEvent.of(
                documentId,
                DocumentStatus.PROCESSING,
                "DOWNLOADING_SOURCE",
                null,
                null,
                "Downloading source document"
        ));

        // 1) Pobranie PDF z MinIO
        byte[] pdfBytes;
        try (var is = minioStorageService.downloadFile(sourceKey)) {
            pdfBytes = is.readAllBytes();
        }

        // 2) Ekstrakcja PDFBox
        documentProgressService.notifyProgress(DocumentProgressEvent.of(
                documentId,
                DocumentStatus.PROCESSING,
                "EXTRACTING_TEXT",
                null,
                null,
                "Extracting PDF text layers"
        ));
        PdfTextExtractionResult extraction = pdfTextExtractionService.extractText(pdfBytes);
        int totalPages = extraction.pageCount();

        // 3) Routing per strona + (opcjonalnie) OCR
        var pageResults = new ArrayList<ExtractedPageResult>(extraction.pageCount());
        boolean needsManualReview = false;

        for (PdfPageText page : extraction.pages()) {
            int pageNum = page.pageNum();
            PageQualityScore quality = pageQualityEvaluator.evaluate(page);
            ExtractedPageResult result;
            if (quality.decision() == RoutingDecision.PDFBOX) {
                result = new ExtractedPageResult(
                        page.pageNum(),
                        "PDFBOX",
                        page.text(),
                        null,
                        quality.warnings());
                documentProgressService.notifyProgress(DocumentProgressEvent.of(
                        documentId,
                        DocumentStatus.PROCESSING,
                        "PAGE_EVALUATED_PDFBOX",
                        pageNum,
                        totalPages,
                        "Page " + pageNum + "/" + totalPages + " accepted via PDFBox"
                ));
            } else {
                documentProgressService.notifyProgress(DocumentProgressEvent.of(
                        documentId,
                        DocumentStatus.PROCESSING,
                        "OCR_PROCESSING_PAGE",
                        pageNum,
                        totalPages,
                        "Page " + pageNum + "/" + totalPages + " running OCR"
                ));
                // OCR_REQUIRED — render + rozpoznanie
                try {
                    var ocr = tess4jOcrService.ocrPage(pdfBytes, page.pageNum() - 1);
                    if (ocr.textPresent()) {
                        result = new ExtractedPageResult(
                                page.pageNum(),
                                "OCR_TESS4J",
                                ocr.text(),
                                ocr.meanConfidence(),
                                quality.warnings());
                    } else {
                        needsManualReview = true;
                        result = new ExtractedPageResult(
                                page.pageNum(),
                                "OCR_TESS4J",
                                "",
                                ocr.meanConfidence(),
                                combine(quality.warnings(),
                                        "OCR nie rozpoznał tekstu — MANUAL_REVIEW"));
                    }
                } catch (Exception ocrErr) {
                    needsManualReview = true;
                    result = new ExtractedPageResult(
                            page.pageNum(),
                            "OCR_TESS4J",
                            "",
                            null,
                            combine(quality.warnings(),
                                    "Błąd OCR: " + ocrErr.getMessage()));
                }
            }
            pageResults.add(result);
        }

        documentProgressService.notifyProgress(DocumentProgressEvent.of(
                documentId,
                DocumentStatus.PROCESSING,
                "SAVING_RESULTS",
                null,
                totalPages,
                "Saving extraction results to storage"
        ));

        // 4) Zapis wyników per strona + podsumowania w MinIO
        for (ExtractedPageResult pr : pageResults) {
            String pageKey = String.format("%s/pages/%03d/final.json",
                    baseKey(documentId), pr.pageNum());
            minioStorageService.uploadJson(pageKey, objectMapper.writeValueAsString(pr));
        }
        DocumentStatus decided = needsManualReview
                ? DocumentStatus.MANUAL_REVIEW
                : DocumentStatus.COMPLETED;
        DocumentResultSummary summary = new DocumentResultSummary(
                documentId, extraction.pageCount(), pageResults,
                decided.code(), Instant.now());
        minioStorageService.uploadJson(
                baseKey(documentId) + "/result.json",
                objectMapper.writeValueAsString(summary));

        // 5) Aktualizacja statusu
        transactionTemplate.executeWithoutResult(status -> {
            Document managed = documentRepository.findById(documentId).orElse(null);
            if (managed == null) {
                return;
            }
            managed.setStatus(decided);
            managed.setErrorMessage(null);
            documentRepository.save(managed);
        });

        // 6) Broadcast final termianl event
        documentProgressService.notifyProgress(DocumentProgressEvent.of(
                documentId,
                decided,
                decided.code(),
                totalPages,
                totalPages,
                decided == DocumentStatus.COMPLETED
                        ? "Document processing completed successfully"
                        : "Document requires manual review"
        ));
        log.info("Przetworzono dokument {} → {}", documentId, decided);
    }

    private void handleFailure(Document document, Exception e) {
        if (document.getId() == null) {
            log.error("Błąd przetwarzania: documentId jest null", e);
            return;
        }
        log.error("Błąd przetwarzania dokumentu {}: {}", document.getId(), e.getMessage(), e);
        DocumentProcessingException toThrow;
        if (e instanceof DocumentProcessingException dpe) {
            toThrow = dpe;
        } else {
            toThrow = new DocumentProcessingException(
                    "Błąd przetwarzania: " + e.getMessage(), e);
        }
        transactionTemplate.executeWithoutResult(status -> {
            Document managed = documentRepository.findById(document.getId()).orElse(null);
            if (managed == null) {
                return;
            }
            int attempts = managed.getProcessingAttempts();
            if (attempts >= maxProcessingAttempts) {
                managed.setStatus(DocumentStatus.FAILED);
                managed.setErrorMessage(truncate(toThrow.getMessage()));
            } else {
                // Zostaw w UPLOADED — OutboxPublisher ponownie opublikuje zdarzenie
                managed.setStatus(DocumentStatus.UPLOADED);
                managed.setErrorMessage(truncate(toThrow.getMessage()));
            }
            managed.setProcessingAttempts(attempts);
            documentRepository.save(managed);
        });
        // Nie rzucamy dalej — błąd został zarejestrowany w statusie dokumentu.
    }

    private String baseKey(UUID documentId) {
        // documents/{id} — wyniki per strona w podścieżce pages/{nnn}
        return "documents/" + documentId;
    }

    private List<String> combine(List<String> a, String b) {
        var list = new ArrayList<String>(a);
        list.add(b);
        return list;
    }

    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
