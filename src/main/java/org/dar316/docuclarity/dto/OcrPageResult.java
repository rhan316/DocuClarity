package org.dar316.docuclarity.dto;

import java.util.List;

/**
 * Wynik OCR dla pojedynczej strony.
 *
 * @param pageNum        numer strony (1-based)
 * @param text           pełny tekst rozpoznany przez OCR
 * @param words          lista słów z per-word confidence
 * @param meanConfidence średnia confidence wszystkich słów (0–100);
 *                       0 gdy brak słów
 * @param textPresent    czy OCR rozpoznał jakikolwiek niepusty tekst
 */
public record OcrPageResult(
        int pageNum,
        String text,
        List<OcrWord> words,
        int meanConfidence,
        boolean textPresent
) {
}
