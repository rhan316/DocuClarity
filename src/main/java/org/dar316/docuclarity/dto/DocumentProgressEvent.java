package org.dar316.docuclarity.dto;

import org.dar316.docuclarity.model.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentProgressEvent(
        UUID documentId,
        DocumentStatus status,
        String stage,
        Integer currentPage,
        Integer totalPages,
        String message,
        Instant timestamp
) {
    public static DocumentProgressEvent of(
            UUID documentId,
            DocumentStatus status,
            String stage,
            Integer currentPage,
            Integer totalPages,
            String message
    ) {
        return new DocumentProgressEvent(
                documentId,
                status,
                stage,
                currentPage,
                totalPages,
                message,
                Instant.now()
        );
    }
}
