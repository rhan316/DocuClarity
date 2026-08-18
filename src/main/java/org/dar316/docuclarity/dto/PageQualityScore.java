package org.dar316.docuclarity.dto;

import java.util.List;

/**
 * Wynik oceny jakości tekstu wyekstrahowanego z pojedynczej strony przez PDFBox.
 *
 * @param pageNum          numer strony (1-based)
 * @param textPresent      czy PDFBox zwrócił niepusty tekst
 * @param charCount        liczba znaków (po strip)
 * @param wordCount        liczba słów
 * @param replacementCharCount liczba znaków zastępczych U+FFFD (nieodwracalne
 *                            błędy kodowania — PDFBox nie mógł zmapować glifu)
 * @param alphaRatio       proporcja znaków alfanumerycznych w tekście (0–1)
 * @param avgWordLength    średnia długość słowa (znaki)
 * @param score            złożony wynik jakości w skali 0–1 (1 = idealny tekst)
 * @param warnings         ostrzeżenia jakościowe (np. obecność U+FFFD)
 * @param decision         decyzja routingu na podstawie score i progów
 */
public record PageQualityScore(
        int pageNum,
        boolean textPresent,
        int charCount,
        int wordCount,
        int replacementCharCount,
        double alphaRatio,
        double avgWordLength,
        double score,
        List<String> warnings,
        RoutingDecision decision
) {
}
