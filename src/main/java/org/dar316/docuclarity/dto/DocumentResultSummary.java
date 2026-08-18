package org.dar316.docuclarity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Podsumowanie wyników przetwarzania dokumentu, zapisywane w MinIO
 * jako documents/{documentId}/result.json.
 *
 * @param documentId  id dokumentu
 * @param pageCount   liczba stron
 * @param pages       wyniki per strona (w kolejności)
 * @param decidedStatus ostateczny status: COMPLETED lub MANUAL_REVIEW
 * @param finishedAt  czas zakończenia przetwarzania
 */
public record DocumentResultSummary(
        UUID documentId,
        int pageCount,
        List<ExtractedPageResult> pages,
        String decidedStatus,
        Instant finishedAt
) {
}
