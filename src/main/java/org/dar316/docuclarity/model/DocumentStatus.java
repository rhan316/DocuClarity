package org.dar316.docuclarity.model;

/**
 * Status przetwarzania dokumentu — zgodny z CHECK constraint tabeli documents
 * (UPLOADED, PROCESSING, COMPLETED, FAILED, MANUAL_REVIEW).
 *
 * Utrzymywany jako enum (silne typowanie w logice), podczas gdy kolumna w DB
 * pozostaje TEXT (zgodnie z decyzją z Etapu 1 — Spring Data JDBC mapuje
 * String na varchar bez custom converterów). Encja Document przechowuje
 * status jako String i eksponuje pomocnicze metody konwersji do/z enumu.
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED,
    MANUAL_REVIEW;

    /** Mapuje nazwę na enum; rzuca IllegalArgumentException dla nieznanej wartości. */
    public static DocumentStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Status dokumentu nie może być null");
        }
        return DocumentStatus.valueOf(code);
    }

    /** Kod przechowywany w DB (nazwa enumu). */
    public String code() {
        return name();
    }
}
