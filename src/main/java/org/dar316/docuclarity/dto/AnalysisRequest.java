package org.dar316.docuclarity.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRequest(
        UUID documentId,
        String extractedTextStorageKey,
        Instant requestedAt
) {
}
