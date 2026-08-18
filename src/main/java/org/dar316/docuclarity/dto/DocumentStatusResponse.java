package org.dar316.docuclarity.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Odpowiedź z metadanymi dokumentu i jego statusem przetwarzania.
 */
public record DocumentStatusResponse(
        UUID documentId,
        String originalFilename,
        String contentType,
        long contentLength,
        String status,
        int processingAttempts,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
