package org.dar316.docuclarity.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Odpowiedź po uploadu dokumentu — zwraca ID i metadane zapisanego dokumentu.
 */
public record UploadResponse(
        UUID documentId,
        String originalFilename,
        String contentType,
        long contentLength,
        String status,
        Instant createdAt
) {
}
