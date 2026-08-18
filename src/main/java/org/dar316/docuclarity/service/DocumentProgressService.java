package org.dar316.docuclarity.service;

import org.dar316.docuclarity.config.MinioProperties;
import org.dar316.docuclarity.dto.DocumentProgressEvent;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service managing client SSE connections and broadcasting processing progress updates.
 */
@Service
public class DocumentProgressService {
    private static final Logger log = LoggerFactory.getLogger(DocumentProgressService.class);
    private static final String EVENT_NAME = "progress";
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final DocumentRepository documentRepository;
    private final long sseTimeoutMs;

    public DocumentProgressService(
            DocumentRepository documentRepository,
            @Value("${docuclarity.sse.timeout-ms:300000}")
            long sseTimeoutMs
    ) {
        this.documentRepository = documentRepository;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /**
     * Registers a new SSE subscription for a document.
     * Immediately emits the current document state to the connected client.
     * @param documentId
     * @return
     */
    public SseEmitter subscribe(UUID documentId) {
        Document document = documentRepository
                .findById(documentId)
                .orElseThrow(
                        () -> new DocumentNotFoundException("Document not found: " + documentId));

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        List<SseEmitter> documentEmitters = emitters.computeIfAbsent(
                documentId, k -> new CopyOnWriteArrayList<>());
        documentEmitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(documentId, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE connection timed out for document {}", documentId);
            emitter.complete();
            removeEmitter(documentId, emitter);
        });
        emitter.onError((e) -> {
            log.debug("SSE error for document {}: {}", documentId, e.getMessage());
            removeEmitter(documentId, emitter);
        });

        // Push initial state immediately
        DocumentStatus status = document.getStatusEnum();
        DocumentProgressEvent initialEvent = DocumentProgressEvent.of(
                documentId,
                status,
                status.code(),
                null,
                null,
                document.getErrorMessage() != null
                                ? document.getErrorMessage()
                                : "Current status: " + status.code()
        );

        sendToEmitter(emitter, initialEvent);

        // If document is already in a terminal state, close the stream
        if (isTerminal(status)) {
            emitter.complete();
            removeEmitter(documentId, emitter);
        }

        return emitter;
    }

    /**
     * Broadcasts a progress event to all active emitters subscribed to the given document
     * @param event
     */
    public void notifyProgress(DocumentProgressEvent event) {
        UUID documentId = event.documentId();
        List<SseEmitter> documentEmitters = emitters.get(documentId);

        if  (documentEmitters == null || documentEmitters.isEmpty()) {
            return;
        }

        for (var emitter : documentEmitters) {
            boolean success = sendToEmitter(emitter, event);
            if (!success) {
                removeEmitter(documentId, emitter);
            } else if (isTerminal(event.status())) {
                emitter.complete();
                removeEmitter(documentId, emitter);
            }
        }
    }

    private boolean sendToEmitter(SseEmitter emitter, DocumentProgressEvent event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(EVENT_NAME)
                        .id(UUID.randomUUID().toString())
                        .data(event, MediaType.APPLICATION_JSON));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send SSE event for document {}: {}",
                        event.documentId(), e.getMessage()
                    );
            return false;
        }
    }

    private void removeEmitter(UUID documentId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(documentId);

        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(documentId);
            }
        }
    }

    private boolean isTerminal(DocumentStatus status) {
        return status == DocumentStatus.COMPLETED
                || status == DocumentStatus.FAILED
                || status == DocumentStatus.MANUAL_REVIEW;
    }

    // Helper for test assertions: returns count of active subscriptions for a document.
    int getActiveEmitterCount(UUID documentId) {
        List<SseEmitter> list = emitters.get(documentId);
        return list != null ? list.size() : 0;
    }
}
