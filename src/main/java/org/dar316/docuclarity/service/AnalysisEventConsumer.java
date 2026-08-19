package org.dar316.docuclarity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.AnalysisRequest;
import org.dar316.docuclarity.dto.AnalysisResult;
import org.dar316.docuclarity.dto.DocumentProgressEvent;
import org.dar316.docuclarity.model.AnalysisStatus;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Konsument zdarzeń completion analizy LLM z workera Python.
 *
 * Worker Python po zakończeniu analizy publikuje JSON (AnalysisResult)
 * do strumienia `docuclarity.analysis.completed`.
 */
@Component
public class AnalysisEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(AnalysisEventConsumer.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepository;
    private final TransactionTemplate transactionTemplate;
    private final DocumentProgressService documentProgressService;
    private final MinioStorageService minioStorageService;
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final String requestStreamKey;
    private final String extractedKeyTemplate;
    private final String analysisResultKeyTemplate;

    private int maxAnalysisAttempts;

    public AnalysisEventConsumer(
            @Qualifier("queueRedisTemplate") RedisTemplate<String, String> redisTemplate,
            @Qualifier("appObjectMapper") ObjectMapper objectMapper,
            DocumentRepository documentRepository,
            TransactionTemplate transactionTemplate,
            DocumentProgressService documentProgressService,
            MinioStorageService minioStorageService,
            @Value("${docuclarity.analysis.complete-stream-key:docuclarity.analysis.completed}")
            String streamKey,
            @Value("${docuclarity.analysis.consumer-group:docuclarity-analyzer}")
            String consumerGroup,
            @Value("${docuclarity.analysis.consumer-name:analyzer-1}")
            String consumerName,
            @Value("${docuclarity.analysis.result-key-template:documents/%s/analysis.json}")
            String analysisResultKeyTemplate,
            @Value("${docuclarity.analysis.request-stream-key:docuclarity.analysis.requested}")
            String requestStreamKey,
            @Value("${docuclarity.analysis.extracted-key-template:documents/%s/result.json}")
            String extractedKeyTemplate,
            @Value("${docuclarity.analysis.max-attempts:3}")
            int maxAnalysisAttempts
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.transactionTemplate = transactionTemplate;
        this.documentProgressService = documentProgressService;
        this.minioStorageService = minioStorageService;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.analysisResultKeyTemplate = analysisResultKeyTemplate;
        this.requestStreamKey = requestStreamKey;
        this.extractedKeyTemplate = extractedKeyTemplate;
        this.maxAnalysisAttempts = maxAnalysisAttempts;
    }

    public void subscribe(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
        ensureConsumerGroup();
        container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                this::onMessage
        );
    }

    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
        } catch (RedisSystemException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists", consumerGroup);
            } else {
                log.warn("Could not create consumer group {}: {}", consumerGroup, e.getMessage());
            }
        }
    }

    private void onMessage(MapRecord<String, String, String> message) {
        try {
            String payload = message.getValue().get("payload");
            if (payload == null) {
                log.warn("Analysis completion event without payload");
                ack(message.getId());
                return;
            }

            var result = objectMapper.readValue(payload, AnalysisResult.class);
            log.info("Received analysis completion for document {}: {}",
                    result.documentId(), result.status());
            handleAnalysisResult(result);
            ack(message.getId());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleAnalysisResult(AnalysisResult result) {
        UUID documentId = result.documentId();
        boolean success = "ANALYZED".equals(result.status());

        if (success) {
            // --- SUKCES: zapis do MinIO + aktualizacja DB ---
            try {
                String storageKey = String.format(analysisResultKeyTemplate, documentId);
                String json = objectMapper.writeValueAsString(result);
                minioStorageService.uploadJson(storageKey, json);
                log.info("Analysis result saved to MinIO: {}", storageKey);
            } catch (JsonProcessingException e) {
                log.error("Failed to save analysis result to MinIO for document {}", documentId, e);
            }

            transactionTemplate.executeWithoutResult(status -> {
                Document managed = documentRepository.findById(documentId).orElse(null);
                if (managed == null) return;

                managed.setAnalysisStatus(AnalysisStatus.ANALYZED);
                managed.setAnalysisModel(result.model());
                managed.setAnalysisErrorMessage(null);
                managed.setAnalysisCompletedAt(Instant.now());

                documentRepository.save(managed);
            });

            notifySse(documentId, "ANALYZED", "LLM analysis completed successfully");
        } else {
            handleAnalysisFailure(documentId, result.errorMessage());
        }
    }

    private void handleAnalysisFailure(UUID documentId, String errorMessage) {
        log.warn("Analysis failed for document {}: {}", documentId, errorMessage);

        final boolean[] shouldRetry = {false};

        transactionTemplate.executeWithoutResult(status -> {
            Document managed = documentRepository.findById(documentId).orElse(null);
            if (managed == null) {
                log.warn("Document {} not found for analysis failure handling", documentId);
                return;
            }

            int attempts = managed.getAnalysisAttempts();
            if (maxAnalysisAttempts > attempts) {
                managed.setAnalysisStatus(AnalysisStatus.ANALYSIS_QUEUED);
                managed.setAnalysisErrorMessage(truncate(errorMessage));

                shouldRetry[0] = true;
                log.info("Analysis retry for document {} attempt {}/{}", documentId, attempts, maxAnalysisAttempts);
            } else {
                // Terminal failure
                managed.setAnalysisStatus(AnalysisStatus.ANALYSIS_FAILED);
                managed.setAnalysisErrorMessage(truncate(errorMessage));
                log.error("Analysis permanently failed for document {} after {} attempts {}",
                        documentId, attempts, maxAnalysisAttempts);
                documentRepository.save(managed);
            }
        });

        if  (shouldRetry[0]) {
            // Re-publish do Redis Stream - dokuemnt ląduje na końcu kolejki
            republishAnalysisRequest(documentId);
            notifySse(documentId, "ANALYSIS_RETRY_QUEUED",
            "Analysis retry queued (will re-attempt)"
                    );
        } else {
            notifySse(documentId, "ANALYSIS_FAILED",
                    "LLM analysis failed permanently " + errorMessage
                    );
        }
    }

    private void republishAnalysisRequest(UUID documentId) {
        try {
            String storageKey = String.format(analysisResultKeyTemplate, documentId);
            AnalysisRequest request = new AnalysisRequest(
                    documentId, storageKey, Instant.now()
            );
            String jsonPayload = objectMapper.writeValueAsString(request);
            Map<String, String> fields = new HashMap<>();
            fields.put("eventType", "ANALYSIS_REQUESTED");
            fields.put("documentId", documentId.toString());
            fields.put("payload", jsonPayload);

            redisTemplate.opsForStream()
                    .add(StreamRecords.string(fields).withStreamKey(re))
        }
    }

    private void notifySse(UUID documentId, String stage, String message) {
        if (documentProgressService != null) {
            documentProgressService.notifyProgress(DocumentProgressEvent.of(
                    documentId,
                    DocumentStatus.COMPLETED,
                    stage,
                    null,
                    null,
                    message
            ));
        }
    }

    private void ack(RecordId id) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, id);
        } catch (Exception e) {
            log.warn("Failed to ACK analysis event {}: {}", id, e.getMessage());
        }
    }

    private String truncate(String msg) {
        return msg != null && msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
