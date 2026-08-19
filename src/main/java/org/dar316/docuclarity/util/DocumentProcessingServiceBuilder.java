package org.dar316.docuclarity.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.service.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

public class DocumentProcessingServiceBuilder {
    private MinioStorageService minioStorageService;
    private PdfTextExtractionService pdfTextExtractionService;
    private PageQualityEvaluator pageQualityEvaluator;
    private Tess4jOcrService tess4jOcrService;
    private DocumentRepository documentRepository;
    private DocumentProgressService documentProgressService;
    private ObjectMapper objectMapper;
    private TransactionTemplate transactionTemplate;
    private int maxProcessingAttempts = 3;
    private AnalysisService analysisService;

    public DocumentProcessingServiceBuilder minioStorageService(MinioStorageService minioStorageService) {
        this.minioStorageService = minioStorageService;
        return this;
    }

    public DocumentProcessingServiceBuilder pdfTextExtractionService(PdfTextExtractionService pdfTextExtractionService) {
        this.pdfTextExtractionService = pdfTextExtractionService;
        return this;
    }

    public DocumentProcessingServiceBuilder pageQualityEvaluator(PageQualityEvaluator pageQualityEvaluator) {
        this.pageQualityEvaluator = pageQualityEvaluator;
        return this;
    }

    public DocumentProcessingServiceBuilder tess4jOcrService(Tess4jOcrService tess4jOcrService) {
        this.tess4jOcrService = tess4jOcrService;
        return this;
    }

    public DocumentProcessingServiceBuilder documentRepository(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
        return this;
    }

    public DocumentProcessingServiceBuilder documentProgressService(DocumentProgressService documentProgressService) {
        this.documentProgressService = documentProgressService;
        return this;
    }

    public DocumentProcessingServiceBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    public DocumentProcessingServiceBuilder transactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
        return this;
    }

    public DocumentProcessingServiceBuilder maxProcessingAttempts(int maxProcessingAttempts) {
        this.maxProcessingAttempts = maxProcessingAttempts;
        return this;
    }

    public  DocumentProcessingServiceBuilder analysisService(AnalysisService analysisService) {
        this.analysisService = analysisService;
        return this;
    }

    public DocumentProcessingService build() {
        Objects.requireNonNull(minioStorageService, "minioStorageService must not be null");
        Objects.requireNonNull(pdfTextExtractionService, "pdfTextExtractionService must not be null");
        Objects.requireNonNull(pageQualityEvaluator, "pageQualityEvaluator must not be null");
        Objects.requireNonNull(tess4jOcrService, "tess4jOcrService must not be null");
        Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        Objects.requireNonNull(documentProgressService, "documentProgressService must not be null");

        return new DocumentProcessingService(
                minioStorageService,
                pdfTextExtractionService,
                pageQualityEvaluator,
                tess4jOcrService,
                documentRepository,
                documentProgressService,
                objectMapper,
                transactionTemplate,
                maxProcessingAttempts,
                analysisService
        );
    }
}
