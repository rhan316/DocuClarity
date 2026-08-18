package org.dar316.docuclarity.dto;

/**
 * Decyzja routingu jakościowego dla pojedynczej strony.
 *
 * Routing jest per strona (nie per dokument) — strona 1 może trafić do
 * PDFBox (tekst dobrej jakości), a strona 3 do OCR. Kolejne etapy
 * (LLM Vision, MANUAL_REVIEW) są zarezerwowane w pipeline, ale decyduje
 * o nich wyższa warstwa na podstawie wyniku OCR.
 */
public enum RoutingDecision {
    /** Tekst z PDFBox ma wystarczającą jakość — akceptuj bez OCR. */
    PDFBOX,

    /** Tekst słabej jakości lub go brak — wymagany OCR (Tess4J). */
    OCR_REQUIRED,

    /**
     * Zarezerwowane: OCR dał niskie confidence / trudny layout —
     * selektywny fallback do LLM Vision (Etap 8).
     */
    LLM_REVIEW,

    /**
     * Zarezerwowane: automat nie daje pewności — wymagana recenzja
     * człowieka (MANUAL_REVIEW).
     */
    MANUAL_REVIEW
}
