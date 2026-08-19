package org.dar316.docuclarity.dto;

import java.util.UUID;

/**
 * REST response dla GET /api/documents/{id}/analysis.
 */
public record AnalysisResponse(
        UUID documentId,
        String analysisStatus,
        String analysisModel,
        String analysisErrorMessage,
        String analysisCompletedAt,
        String analysisStorageKey // np. documents/{id}/analysis.json
) {
}
