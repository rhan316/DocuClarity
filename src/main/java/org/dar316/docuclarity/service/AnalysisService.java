package org.dar316.docuclarity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.AnalysisRequest;
import org.dar316.docuclarity.dto.DocumentProgressEvent;
import org.dar316.docuclarity.model.AnalysisStatus;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publikuje żądania analizy LLM do Redis Streams dla workera Python.
 *
 * Wywołania automatycznie przez DocumentProcessingService po zakończeniu
 * ekstradycji (status COMPLETED)
 */
@Service
public class AnalysisService {
    private static final Logger log =  LoggerFactory.getLogger(AnalysisService.class);

    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DocumentProgressService documentProgressService;
    private final String requestStreamKey;
    private final String extractedKeyTemplate;

    public AnalysisService(
            DocumentRepository documentRepository,
            @Qualifier("queueRedisTemplate") RedisTemplate<String, String> redisTemplate,
            @Qualifier("appObjectMapper") ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            DocumentProgressService documentProgressService,
            @Value("${docuclarity.analysis.request-stream-key:docuclarity.analysis.requested}")
            String requestStreamKey,
            @Value("${docuclarity.analysis.extracted-key-template:documents/%s/result.json}")
            String extractedKeyTemplate
    ) {
        this.documentRepository = documentRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.documentProgressService = documentProgressService;
        this.requestStreamKey = requestStreamKey;
        this.extractedKeyTemplate = extractedKeyTemplate;
    }

    /*
    Automatycznie inicjuje analizę LLM po zakończeniu ekstracji.
    Wywołanie przez DocumentProcessingService gdy status = COMPLETED.
     */
    public void requestAnalysis(UUID documentId) {
        requestAnalysisInternal(documentId, true);
    }

    // Ręcznie inicjuje analizę LLM (np. przez REST endpoint)
    public void requestAnalysis(UUID documentId, boolean autoTriggered) {
        requestAnalysisInternal(documentId, autoTriggered);
    }

    private void requestAnalysisInternal(UUID documentId, boolean autoTriggered) {
        log.info("Requesting LLM analysis for document: {} (autoTriggered={})", documentId, autoTriggered);

        final boolean[] shouldSkip = {false};

        // 1. Validation and atomic status update within a transaction
        transactionTemplate.executeWithoutResult(status -> {
            Document managed = documentRepository.findById(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));
            DocumentStatus docStatus = managed.getStatusEnum();

            if (docStatus != DocumentStatus.COMPLETED) {
                throw new DocumentProcessingException(
                        "Cannot analyze document in status " + docStatus + " must be COMPLETED");
            }

            AnalysisStatus currentAnalysis = managed.getAnalysisStatusEnum();

            if (currentAnalysis == AnalysisStatus.ANALYSIS_QUEUED
                    || currentAnalysis == AnalysisStatus.ANALYZING) {
                log.info("Document {} already has analysis in progress (status={}), skipping",
                        documentId, currentAnalysis);
                shouldSkip[0] = true;
                return;
            }

            managed.setAnalysisStatus(AnalysisStatus.ANALYSIS_QUEUED);
            managed.setAnalysisErrorMessage(null);
            documentRepository.save(managed);
        });

        if (shouldSkip[0]) {
            log.info("Skipping analysis publish for document: {} - already in progress", documentId);
            return;
        }

        // 2. Publikacja żądania do Redis Streams
        String storageKey = String.format(extractedKeyTemplate, documentId);
        AnalysisRequest request = new AnalysisRequest(
                documentId,
                storageKey,
                Instant.now()
        );

        try {
            String jsonPayload = objectMapper.writeValueAsString(request);
            Map<String, String> fields = new HashMap<>();
            fields.put("eventType", "ANALYSIS_REQUESTED");
            fields.put("documentId", documentId.toString());
            fields.put("payload", jsonPayload);

            redisTemplate.opsForStream()
                    .add(
                            StreamRecords.string(fields)
                                    .withStreamKey(requestStreamKey)
                    );
            log.info("Analysis request published for document {}, storageKey={}", documentId, requestStreamKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize analysis request for document: {}", documentId, e);
            transactionTemplate.executeWithoutResult(status -> {
                Document managed = documentRepository.findById(documentId).orElse(null);
                if (managed != null) {
                    managed.setAnalysisStatus(AnalysisStatus.NOT_ANALYZED);
                    documentRepository.save(managed);
                }
            });
            throw new AnalysisException("Failed to publish analysis request: " + e.getMessage(), e);
        }

        // 3. Powiadomienie SSE
        if (documentProgressService != null) {
            documentProgressService.notifyProgress(DocumentProgressEvent.of(
                    documentId,
                    DocumentStatus.COMPLETED,
                    "ANALYSIS_QUEUED",
                    null,
                    null,
                    "LLM analysis queued (auto-triggered: " + autoTriggered + ")"
            ));
        }
    }
}
