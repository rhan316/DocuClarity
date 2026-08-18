package org.dar316.docuclarity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Konsument zdarzeń z Redis Streams (consumer group) — odbiera DOCUMENT_UPLOADED
 * i deleguje przetwarzanie do DocumentProcessingService przez TaskExecutor
 * (OCR/Tesseract jest CPU-bound, nie blokujemy wątku nasłuchu).
 *
 * <p>Używa StreamMessageListenerContainer z consumer group — Redis gwarantuje
 * dostarczenie co najwyżej raz-przetworzenie przy poprawnym ACK. Brak ACK
 * (crash) → pending entry, ponowne dostarczenie przez innego/tego samego
 * konsumenta (retry na poziomie streamu).</p>
 */
@Component
public class StreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DocumentProcessingService processingService;
    private final org.springframework.core.task.TaskExecutor taskExecutor;
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;

    public StreamConsumer(@org.springframework.beans.factory.annotation.Qualifier("queueRedisTemplate")
                          RedisTemplate<String, String> redisTemplate,
                          DocumentProcessingService processingService,
                          @org.springframework.beans.factory.annotation.Qualifier("processingTaskExecutor")
                          org.springframework.core.task.TaskExecutor taskExecutor,
                          @Value("${docuclarity.queue.stream-key:docuclarity.documents}")
                          String streamKey,
                          @Value("${docuclarity.queue.consumer-group:docuclarity-workers}")
                          String consumerGroup,
                          @Value("${docuclarity.queue.consumer-name:worker-1}")
                          String consumerName) {
        this.redisTemplate = redisTemplate;
        this.processingService = processingService;
        this.taskExecutor = taskExecutor;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
    }

    /**
     * Rejestruje listener na strumieniu przy starcie kontenera.
     * Wywoływane przez Configuration (zależność od gotowego StreamMessageListenerContainer).
     */
    public void subscribe(StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
        ensureConsumerGroup();
        container.receive(
                org.springframework.data.redis.connection.stream.Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                this::onMessage);
    }

    private void ensureConsumerGroup() {
        try {
            redisTemplate
                    .opsForStream()
                    .createGroup(streamKey, consumerGroup);
        } catch (RedisSystemException e) {
            if (e.getCause() instanceof RedisSystemException
                    && e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")
            ) {
                log.debug("Consumer group {} already exists", consumerGroup);
            } else {
                log.warn("Failed to create consumer group {}", consumerGroup);
            }
        }
    }

    private void onMessage(MapRecord<String, String, String> message) {
        RecordId id = message.getId();
        try {
            String documentIdStr = message.getValue().get("documentId");
            if (documentIdStr == null) {
                log.warn("Zdarzenie {} bez documentId — ACK i skip", id);
                ack(id);
                return;
            }
            UUID documentId = UUID.fromString(documentIdStr);
            log.info("Odebrano zdarzenie {} dla dokumentu {}", id, documentId);
            // Delegacja do workera (CPU-bound OCR w osobnym wątku)
            taskExecutor.execute(() -> {
                try {
                    processingService.process(documentId);
                    ack(id);
                } catch (Exception e) {
                    log.error("Błąd workera dla dokumentu {} (zdarzenie {}): {}",
                            documentId, id, e.getMessage(), e);
                    // Brak ACK → Redis ponownie dostarczy (retry)
                }
            });
        } catch (Exception e) {
            log.error("Błąd obsługi zdarzenia {}: {}", id, e.getMessage(), e);
            // Brak ACK → retry
        }
    }

    private void ack(RecordId id) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, id);
        } catch (Exception e) {
            log.warn("Nie udało się ACK zdarzenia {}: {}", id, e.getMessage());
        }
    }
}
