package org.dar316.docuclarity.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.AnalysisRequest;
import org.dar316.docuclarity.dto.DocumentProgressEvent;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.service.DocumentProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class AnalysisRetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AnalysisRetryScheduler.class);

    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentProgressService documentProgressService;
    private final String requestStreamKey;
    private final String extractedKeyTemplate;
    private final int staleThresholdSeconds;

    public AnalysisRetryScheduler(
            DocumentRepository documentRepository,
            @Qualifier("queueRedisTemplate") RedisTemplate<String, String> redisTemplate,
            @Qualifier("appObjectMapper") ObjectMapper objectMapper,
            @Value("${docuclarity.analysis.request-stream-key:docuclarity.analysis.requested}")
            String requestStreamKey,
            @Value("${docuclarity.analysis.extracted-key-template:documents/%s/result.json}")
            String extractedKeyTemplate,
            @Value("${docularity.analysis.stale-threshold-seconds:60}")
            int staleThresholdSeconds
    ) {
        this.documentRepository = documentRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.requestStreamKey = requestStreamKey;
        this.extractedKeyTemplate = extractedKeyTemplate;
        this.staleThresholdSeconds = staleThresholdSeconds;
    }

    private void republishAnalysisRequest(Document document) {
        try {
            String storageKey = String.format(requestStreamKey, document.getId());
            AnalysisRequest request = new AnalysisRequest(
                    document.getId(),
                    storageKey,
                    Instant.now()
            );

            String jsonPayload = objectMapper.writeValueAsString(request);
            Map<String, String> fields = new HashMap<>();
            fields.put("eventType", "ANALYSIS_REQUESTED");
            fields.put("documentId", document.getId().toString());
            fields.put("payload", jsonPayload);

            redisTemplate.opsForStream()
                    .add(StreamRecords
                            .string(fields)
                            .withStreamKey(requestStreamKey));

            log.info("Re-published stale analysis request for document {} (attempts={}",
                    document.getId(), document.getAnalysisAttempts());

            if (documentProgressService != null) {
                documentProgressService.notifyProgress(DocumentProgressEvent.of(
                        document.getId(),
                        DocumentStatus.COMPLETED,
                        "ANALYSIS_RETRY_PUBLISHED",
                        null,
                        null,
                        "Analysis re-queued by scheduler (attempt " +
                                (document.getAnalysisAttempts() + 1) + ")"
                ));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize analysis request for document {}", document.getId(), e);
        }
    }
}
