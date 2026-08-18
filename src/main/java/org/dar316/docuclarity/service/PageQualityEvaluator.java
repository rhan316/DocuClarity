package org.dar316.docuclarity.service;

import org.dar316.docuclarity.dto.PageQualityScore;
import org.dar316.docuclarity.dto.PdfPageText;
import org.dar316.docuclarity.dto.PdfTextExtractionResult;
import org.dar316.docuclarity.dto.RoutingDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ocenia jakość tekstu wyekstrahowanego z PDF przez PDFBox i decyduje o routingu
 * jakościowym per strona (zgodnie z Etapem 3 i sekcją 5 karty projektu).
 *
 * <p>Zasada z karty: {@code textPresent == true} z PDFBox NIE oznacza dobrej
 * jakości — potrzebny jest scoring (długość, słowa, znaki zastępcze, layout).
 * Stąd ocena składa się z kilku metryk złączonych w wynik 0–1:</p>
 * <ul>
 *   <li>liczba słów (czy strona w ogóle zawiera tekst),</li>
 *   <li>proporcja znaków alfanumerycznych (czy to tekst, a nie artefakty),</li>
 *   <li>liczba znaków zastępczych U+FFFD (nieodwracalne błędy kodowania glifów),</li>
 *   <li>średnia długość słowa (krótkie "śmieciowe" tokeny obniżają pewność).</li>
 * </ul>
 *
 * <p>Decyzja: {@code score >= acceptThreshold} → {@link RoutingDecision#PDFBOX},
 * w przeciwnym razie {@link RoutingDecision#OCR_REQUIRED}. Wartości
 * {@link RoutingDecision#LLM_REVIEW} i {@link RoutingDecision#MANUAL_REVIEW}
 * są zarezerwowane dla wyższej warstwy pipeline (po OCR / na podstawie confidence).</p>
 *
 * <p>Progi ({@code docuclarity.quality.*}) to wartości robocze — wymagają
 * kalibracji na realnym korpusie dokumentów.</p>
 */
@Service
public class PageQualityEvaluator {

    /** Wynik ≥ tej wartości akceptuje tekst PDFBox bez OCR. */
    private final double acceptThreshold;
    /** Minimalna liczba słów, by strona była traktowana jako tekstowa. */
    private final int minWordCount;
    /** Liczba słów traktowana jako "pełny" tekst (skalowanie wordCountFactor). */
    private final int idealWordCount;
    /** Udział znaków U+FFFD powyżej którego strona jest silnie obniżana. */
    private final double maxReplacementRatio;

    public PageQualityEvaluator(
            @Value("${docuclarity.quality.accept-threshold:0.85}") double acceptThreshold,
            @Value("${docuclarity.quality.min-word-count:5}") int minWordCount,
            @Value("${docuclarity.quality.ideal-word-count:20}") int idealWordCount,
            @Value("${docuclarity.quality.max-replacement-ratio:0.05}") double maxReplacementRatio
    ) {
        if (acceptThreshold < 0.0 || acceptThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "accept-threshold musi być w przedziale [0,1]: " + acceptThreshold);
        }
        if (maxReplacementRatio < 0.0 || maxReplacementRatio > 1.0) {
            throw new IllegalArgumentException(
                    "max-replacement-ratio musi być w przedziale [0,1]: " + maxReplacementRatio);
        }
        this.acceptThreshold = acceptThreshold;
        this.minWordCount = minWordCount;
        this.idealWordCount = idealWordCount;
        this.maxReplacementRatio = maxReplacementRatio;
    }

    /**
     * Konstruktor bezargumentowy z domyślnymi progami — wygoda dla testów
     * jednostkowych (bez Springa). W runtime Spring używa konstruktora
     * 4-argumentowego z {@code @Value}.
     */
    public PageQualityEvaluator() {
        this(0.85, 5, 20, 0.05);
    }

    /**
     * Ocenia jakość pojedynczej strony.
     *
     * @param page wynik ekstrakcji PDFBox dla jednej strony (nie może być null)
     * @return wynik oceny z metrykami, score i decyzją routingu
     */
    public PageQualityScore evaluate(PdfPageText page) {
        if (page == null) {
            throw new IllegalArgumentException("PdfPageText nie może być null");
        }

        String text = page.text() != null ? page.text() : "";
        boolean textPresent = page.textPresent();

        int replacementCharCount = countReplacementChars(text);
        double alphaRatio = computeAlphaRatio(text);
        double avgWordLength = computeAvgWordLength(text);

        var warnings = new ArrayList<String>();
        if (replacementCharCount > 0) {
            warnings.add("Znaki zastępcze U+FFFD (błędy kodowania glifów): "
                    + replacementCharCount);
        }
        if (textPresent && page.wordCount() < minWordCount) {
            warnings.add("Za mało słów (" + page.wordCount()
                    + " < " + minWordCount + ")");
        }

        // --- Czynniki składowe (każdy w przedziale 0–1) ---
        double wordCountFactor = clamp(
                (double) page.wordCount() / idealWordCount, 0.0, 1.0);
        double alphaRatioFactor = alphaRatio;
        double replacementPenaltyFactor = computeReplacementPenalty(text, replacementCharCount);
        double avgWordLengthFactor = clamp(avgWordLength / 6.0, 0.0, 1.0);

        // --- Złożony wynik (wagowany) ---
        double score = wordCountFactor * 0.50
                + alphaRatioFactor * 0.20
                + replacementPenaltyFactor * 0.20
                + avgWordLengthFactor * 0.10;

        // Brak tekstu => wymuszony OCR (score i tak bliski 0).
        if (!textPresent) {
            score = 0.0;
        }
        score = clamp(score, 0.0, 1.0);

        RoutingDecision decision = score >= acceptThreshold
                ? RoutingDecision.PDFBOX
                : RoutingDecision.OCR_REQUIRED;

        return new PageQualityScore(
                page.pageNum(),
                textPresent,
                page.charCount(),
                page.wordCount(),
                replacementCharCount,
                alphaRatio,
                avgWordLength,
                score,
                List.copyOf(warnings),
                decision
        );
    }

    /**
     * Ocenia wszystkie strony wyniku ekstrakcji PDFBox.
     *
     * @param result wynik ekstrakcji całego dokumentu
     * @return lista ocen per strona (w kolejności stron)
     */
    public List<PageQualityScore> evaluate(PdfTextExtractionResult result) {
        if (result == null) {
            throw new IllegalArgumentException("PdfTextExtractionResult nie może być null");
        }
        var scores = new ArrayList<PageQualityScore>(result.pages().size());
        for (PdfPageText page : result.pages()) {
            scores.add(evaluate(page));
        }
        return scores;
    }

    // --- Metryki tekstu ---

    private int countReplacementChars(String text) {
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (cp == 0xFFFD) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    private double computeAlphaRatio(String text) {
        if (text.isEmpty()) {
            return 0.0;
        }
        int alpha = 0;
        int total = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            total++;
            if (Character.isLetterOrDigit(cp)) {
                alpha++;
            }
            i += Character.charCount(cp);
        }
        return total == 0 ? 0.0 : (double) alpha / total;
    }

    private double computeAvgWordLength(String text) {
        if (text.isBlank()) {
            return 0.0;
        }
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length == 0) {
            return 0.0;
        }
        int sum = 0;
        for (String t : tokens) {
            sum += t.length();
        }
        return (double) sum / tokens.length;
    }

    /**
     * Czynnik kary za znaki zastępcze: 1.0 gdy brak U+FFFD, liniowo spada do 0
     * przy udziale {@code maxReplacementRatio} (i pozostaje 0 powyżej).
     */
    private double computeReplacementPenalty(String text, int replacementCharCount) {
        if (replacementCharCount == 0) {
            return 1.0;
        }
        int len = text.length();
        if (len == 0) {
            return 0.0;
        }
        double ratio = (double) replacementCharCount / len;
        return clamp(1.0 - ratio / maxReplacementRatio, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
