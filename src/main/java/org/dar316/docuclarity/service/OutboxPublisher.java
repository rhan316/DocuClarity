package org.dar316.docuclarity.service;

import org.dar316.docuclarity.model.OutboxEntry;
import org.dar316.docuclarity.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publisher zdarzeń z outbox na Redis Streams (Transactional Outbox pattern).
 *
 * <p>Działa w pętli (scheduler): odczytuje wpisy outbox ze statusem PENDING,
 * publikuje je na Redis Streams (XADD) i zaznacza jako PUBLISHED w tej samej
 * transakcji DB. Dzięki temu publikacja jest idempotentna — jeśli worker
 * przetworzy zdarzenie, a publisher upadnie przed zapisem PUBLISHED, wpis
 * zostanie ponownie opublikowany (Redis Streams z consumer group gwarantuje
 * co najwyżej raz-przetworzenie przy poprawnym AKC pozytywnym).</p>
 *
 * <p>Błąd publikacji nie przerywa pętli — wpis pozostaje PENDING i zostanie
 * ponowiony w kolejnym cyklu (retry na poziomie publikacji przez pole attempts).</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String streamKey;
    private final int maxPublishAttempts;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           @org.springframework.beans.factory.annotation.Qualifier("queueRedisTemplate")
                           RedisTemplate<String, String> redisTemplate,
                           TransactionTemplate transactionTemplate,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${docuclarity.queue.stream-key:docuclarity.documents}")
                           String streamKey,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${docuclarity.queue.max-publish-attempts:5}")
                           int maxPublishAttempts) {
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
        this.streamKey = streamKey;
        this.maxPublishAttempts = maxPublishAttempts;
    }

    @Scheduled(fixedDelayString = "${docuclarity.queue.publish-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEntry> pending = outboxRepository.findPending();
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEntry entry : pending) {
            publishOne(entry);
        }
    }

    private void publishOne(OutboxEntry entry) {
        try {
            // 1) Publikacja na Redis Streams (poza transakcją DB)
            Map<String, String> fields = new HashMap<>();
            fields.put("eventType", entry.getEventType());
            fields.put("documentId", entry.getDocumentId().toString());
            fields.put("payload", entry.getPayload());
            redisTemplate.opsForStream()
                    .add(StreamRecords.string(fields).withStreamKey(streamKey));

            // 2) Oznaczenie jako PUBLISHED w transakcji DB
            transactionTemplate.executeWithoutResult(status -> {
                OutboxEntry managed = outboxRepository.findById(entry.getId())
                        .orElse(null);
                if (managed == null || !"PENDING".equals(managed.getStatus())) {
                    // W międzyczasie przetworzone przez inną instancję — ignoruj
                    return;
                }
                managed.setStatus("PUBLISHED");
                managed.setPublishedAt(Instant.now());
                outboxRepository.save(managed);
            });
        } catch (Exception e) {
            handlePublishFailure(entry, e);
        }
    }

    private void handlePublishFailure(OutboxEntry entry, Exception e) {
        int attempts = entry.getAttempts() + 1;
        log.warn("Błąd publikacji outbox {} (próba {}): {}",
                entry.getId(), attempts, e.getMessage());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxEntry managed = outboxRepository.findById(entry.getId())
                        .orElse(null);
                if (managed == null) {
                    return;
                }
                managed.setAttempts(attempts);
                managed.setErrorMessage(e.getMessage());
                if (attempts >= maxPublishAttempts) {
                    managed.setStatus("FAILED");
                }
                outboxRepository.save(managed);
            });
        } catch (Exception dbErr) {
            log.error("Nie udało się zapisać statusu błędu publikacji dla {}",
                    entry.getId(), dbErr);
        }
    }
}
