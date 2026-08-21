package org.dar316.docuclarity.dto;

/**
 * Wykryty kruczek prawny w dokumencie.
 *
 * @param title Zwięzły tytuł (np. "Automatyczne przedłużenie umowy")
 * @param quote Oryginalny cytat z dokumentu
 * @param explanation Wyjaśnienie dlaczego to problem
 * @param severity LOW, MEDIUM, HIGH, CRITICAL
 * @param pageNumber Number strony (nullable jeśli nieznany)
 */
public record LegalPitfall(
        String title,
        String quote,
        String explanation,
        String severity,
        Integer pageNumber,
        String verification
) {
}
