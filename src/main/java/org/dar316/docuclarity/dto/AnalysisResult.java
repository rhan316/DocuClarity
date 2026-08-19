package org.dar316.docuclarity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pełny wynik analizy LLM.
 * Otrzymywany z workera Python przez Redis Stream.
 * Zapisywany w MinIO jako documents/{id}/analysis.json.
 *
 * @param documentId
 * @param status "ANALYZED" or "ANALYZED_FAILED"
 * @param plainText Uproszczona wersja dokumentu
 * @param summary 1-stronicowe podsumowanie
 * @param pitfalls Wykryte kruczki
 * @param model model LLM np. google/gemma-4-27b
 * @param errorMessage null jeśli sukces
 * @param completedAt
 */
public record AnalysisResult(
        UUID documentId,
        String status,
        String plainText,
        IndividualSummary summary,
        List<LegalPitfall> pitfalls,
        String model,
        String errorMessage,
        Instant completedAt
) {
}
