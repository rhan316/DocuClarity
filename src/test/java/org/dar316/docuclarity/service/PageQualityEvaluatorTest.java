package org.dar316.docuclarity.service;

import org.dar316.docuclarity.dto.PageQualityScore;
import org.dar316.docuclarity.dto.PdfPageText;
import org.dar316.docuclarity.dto.PdfTextExtractionResult;
import org.dar316.docuclarity.dto.RoutingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe dla PageQualityEvaluator.
 *
 * Nie używają Springa ani Testcontainers — instancja przez {@code new}.
 */
class PageQualityEvaluatorTest {

    // =========================================================================
    // Sekcja 1 — domyślne konfiguracje
    // =========================================================================

    @Nested
    @DisplayName("Domyślna konfiguracja (acceptThreshold=0.85, minWordCount=5, idealWordCount=20, maxReplacementRatio=0.05)")
    class DefaultConfigTest {

        private final PageQualityEvaluator sut = new PageQualityEvaluator();

        /** 1. Strona pusta -> score 0.0, decision OCR_REQUIRED */
        @Test
        @DisplayName("Pusta strona — score 0.0, decyduje na OCR_REQUIRED")
        void emptyPage() {
            // given
            PdfPageText page = new PdfPageText(1, "", 0, 0, false);

            // when
            PageQualityScore score = sut.evaluate(page);

            // then
            assertEquals(0.0, score.score(), 0.0001);
            assertFalse(score.textPresent());
            assertEquals(RoutingDecision.OCR_REQUIRED, score.decision());
        }

        /** 2. Dobry tekst (>=20 słów, wszystkie literowe, średnia dł. >6) -> score=1.0, PDFBOX */
        @Test
        @DisplayName("Dobry tekst — score >= acceptThreshold, decyduje na PDFBOX")
        void goodText() {
            // given — 20 słów, średnia dł. ~8,59, alphaRatio=1.0, brak U+FFFD
            String text = ("Dokument zawiera treść umowy oraz zobowiązania stron wynikające "
                    + "z zapisów i postanowień zawartych w niniejszym dokumencie prawnym.");
            int wordCount = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
            PdfPageText page = new PdfPageText(1, text, text.length(), wordCount, true);

            // when
            PageQualityScore score = sut.evaluate(page);

            // then
            assertEquals(wordCount, score.wordCount());
            assertTrue(score.alphaRatio() > 0.75, "alphaRatio tekstu ze spacjami jest wysoki (~0.8)");
            assertTrue(score.score() >= 0.85, "score >= acceptThreshold (tekst z kropką daje alphaRatio<1)");
            assertEquals(RoutingDecision.PDFBOX, score.decision());
        }

        /** 3. Tekst ze znakami U+FFFD -> replacementCharCount>0, obniżony score, warning */
        @Test
        @DisplayName("Tekst ze znakami U+FFFD — ostrzeżenie i obniżony score")
        void textWithReplacementChars() {
            // given — tekst z wbudowanymi znakami kodowania
            String goodStart = "Raport roczny za rok 2025 przedstawia wyniki finansowe.";
            String badEnd = "Szczegóły znajdują się ";
            String text = goodStart + "\uFFFD\uFFFD\uFFFD" + badEnd;
            int startLen = goodStart.length() + badEnd.length();
            int endLen = "\uFFFD".length(); // 2 bajty UTF-16 na jeden code point
            PdfPageText page = new PdfPageText(1, text, text.length(), startLen, true);

            // when
            PageQualityScore score = sut.evaluate(page);

            // then
            assertAll(
                    () -> assertTrue(score.replacementCharCount() > 0),
                    () -> assertTrue(score.warnings().stream()
                            .anyMatch(w -> w.contains("U+FFFD"))),
                    () -> {
                        // porównanie z tą samą treścią bez FFFD daje wyższy score
                        PageQualityScore clean = sut.evaluate(
                                new PdfPageText(1, goodStart + badEnd,
                                        goodStart.length() + badEnd.length(),
                                        startLen, true));
                        assertTrue(clean.score() > score.score(),
                                "score powinien spać po dodaniu FFFD");
                    }
            );
        }
    }

    // =========================================================================
    // Sekcja 2 — próg decyzji i zmiana parametru acceptThreshold
    // =========================================================================

    @Nested
    @DisplayName("Zmiana progu decyzyjnego (acceptThreshold)")
    class ThresholdTest {

        /**
         * 4. Próg: przy bardzo wysokim progu (0.99) dobry tekst dostaje OCR_REQUIRED;
         *    przy niskim (0.0) — PDFBOX. Pokazuje, że decyzja zależy od progu, nie
         *    od surowego score.
         */
        @Test
        @DisplayName("Próg decyzji — wysoki i niski acceptThreshold")
        void thresholdBoundary() {
            // given — ten sam tekst dobrej jakości
            String text = ("Dokument zawiera treść umowy oraz zobowiązania stron wynikające "
                    + "z zapisów i postanowień zawartych w niniejszym dokumencie prawnym.");
            int wc = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
            PdfPageText good = new PdfPageText(1, text, text.length(), wc, true);
            PdfPageText bad = new PdfPageText(2, "", 0, 0, false);

            // when — threshold 0.99 (niemożliwie wysoki)
            var evHigh = new PageQualityEvaluator(0.99, 5, 20, 0.05);
            PageQualityScore goodHigh = evHigh.evaluate(good);

            // when — threshold 0.0 (każdy tekst_present akceptowalny)
            var evLow = new PageQualityEvaluator(0.0, 5, 20, 0.05);
            PageQualityScore goodLow = evLow.evaluate(good);
            PageQualityScore badLow = evLow.evaluate(bad);

            // then
            assertEquals(RoutingDecision.OCR_REQUIRED, goodHigh.decision(),
                    "przy wysokim progu dobry tekst też odrzuca");
            assertEquals(RoutingDecision.PDFBOX, goodLow.decision(),
                    "przy niskim progu dobry tekst akceptuje");
            assertEquals(RoutingDecision.PDFBOX, badLow.decision(),
                    "przy progu 0.0 tekstPresent=True zawsze PDFBOX");
        }
    }

    // =========================================================================
    // Sekcja 3 — ostrzeżenia i metryki pośrednie
    // =========================================================================

    @Nested
    @DisplayName("Ostrzeżenia i metryki pośrednie (warnings, alphaRatio, avgWordLength)")
    class WarningAndMetricsTest {

        /** 5. Za mało słów -> warning zawiera \"Za mało słów\" */
        @Test
        @DisplayName("Za mało słów — ostrzeżenie o niewystarczającej liczbie słów")
        void tooFewWordsWarning() {
            // given — tylko 3 słowa (< minWordCount=5)
            PdfPageText page = new PdfPageText(1, "Jakość tekstu jest niska.", 26, 3, true);

            // when
            PageQualityScore score = new PageQualityEvaluator(0.85, 5, 20, 0.05)
                    .evaluate(page);

            // then
            assertTrue(score.warnings().stream()
                    .anyMatch(w -> w.contains("Za mało słów")),
                    "warning musi zawierać 'Za mało słów'");
        }

        /** 5b. Za mało słów przy customowym minWordCount=10 */
        @Test
        @DisplayName("Za mało słów z customowym minWordCount — ostrzeżenie")
        void tooFewWordsCustomMin() {
            // given — 7 słów < customowy minWordCount=10
            PdfPageText page = new PdfPageText(1,
                    "Krótki tekst sprawdzający ostrzeżenie błędu.", 53, 7, true);

            // when
            PageQualityScore score = new PageQualityEvaluator(0.85, 10, 20, 0.05)
                    .evaluate(page);

            // then
            assertTrue(score.warnings().stream()
                    .anyMatch(w -> w.contains("Za mało słów")));
        }

        /** 6. alphaRatio: litery same -> 1.0, znaki specjalne -> ~0.0 */
        @Test
        @DisplayName("alphaRatio — czyste litery vs znaki specjalne")
        void alphaRatio() {
            // given
            var ev = new PageQualityEvaluator(0.0, 5, 20, 0.05);
            PdfPageText alphaOnly = new PdfPageText(1, "ABCDEFGHI", 9, 1, true);
            PdfPageText special = new PdfPageText(2, "%%%%%% #### @@@", 15, 3, true);

            // when
            PageQualityScore alpha = ev.evaluate(alphaOnly);
            PageQualityScore spe = ev.evaluate(special);

            // then
            assertEquals(1.0, alpha.alphaRatio(), 0.0001,
                    "same litery+digi dają alphaRatio = 1.0");
            assertEquals(0.0, spe.alphaRatio(), 0.0001,
                    "same znaki specjalne dają alphaRatio = 0.0");
        }

        /** 7. avgWordLength: krótkie słowa vs długie słowa */
        @Test
        @DisplayName("avgWordLength — krótkie i długie słowa")
        void avgWordLength() {
            // given
            var ev = new PageQualityEvaluator(0.0, 5, 20, 0.05);
            // "a b c" -> średnia 1.0
            PdfPageText shortWords = new PdfPageText(1, "a b c", 5, 3, true);
            // "supercalifragilisticexpialidocious" -> średnia ~34
            PdfPageText longWords = new PdfPageText(2,
                    "supercalifragilisticexpialidocious elephantomaniacalypsodiatricalisticexpiadocious",
                    110, 2, true);

            // when
            PageQualityScore sw = ev.evaluate(shortWords);
            PageQualityScore lw = ev.evaluate(longWords);

            // then
            assertTrue(sw.avgWordLength() < 2.0,
                    "średnia krótka < 2");
            assertTrue(lw.avgWordLength() > 30.0,
                    "średnia długa > 30");
            assertTrue(lw.avgWordLength() > sw.avgWordLength(),
                    "długie > krótkie");
        }
    }

    // =========================================================================
    // Sekcja 4 — batch / evaluate(PdfTextExtractionResult)
    // =========================================================================

    @Nested
    @DisplayName("Ocena wsadowa — evaluate(PdfTextExtractionResult)")
    class BatchEvaluationTest {

        /** 8. Dwie strony: dobra + pusta -> kolejna lista ocen, poprawne decyzje */
        @Test
        @DisplayName("Batch: dobra strona + pusta -> lista 2 ocen w kolejności")
        void batchMixedPages() {
            // given
            String good = ("Dokument zawiera treść umowy oraz zobowiązania stron wynikające "
                    + "z zapisów i postanowień zawartych w niniejszym dokumencie prawnym.");
            int wc = good.trim().isEmpty() ? 0 : good.trim().split("\\s+").length;
            PdfPageText goodPage = new PdfPageText(1, good, good.length(), wc, true);
            PdfPageText emptyPage = new PdfPageText(2, "", 0, 0, false);

            PdfTextExtractionResult result = new PdfTextExtractionResult(
                    2, List.of(goodPage, emptyPage), good);

            // when
            List<PageQualityScore> scores = new PageQualityEvaluator()
                    .evaluate(result);

            // then
            assertEquals(2, scores.size());
            assertEquals(RoutingDecision.PDFBOX, scores.get(0).decision(),
                    "pierwsza strona powinna trafić do PDFBox");
            assertEquals(RoutingDecision.OCR_REQUIRED, scores.get(1).decision(),
                    "druga strona powinna trafić na OCR");
            assertEquals(1, scores.get(0).pageNum());
            assertEquals(2, scores.get(1).pageNum());
            assertEquals(0.0, scores.get(1).score(), 0.0001);
        }
    }

    // =========================================================================
    // Sekcja 5 — null-inputs i walidacja parametrów konstruktora
    // =========================================================================

    @Nested
    @DisplayName("Walidacja argumentów — null i zakresy")
    class ValidationTest {

        /** 9. null page -> IllegalArgumentException */
        @Test
        @DisplayName("null jako PdfPageText rzuca IllegalArgumentException")
        void nullPageThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator().evaluate((PdfPageText) null));
        }

        /** 10. null result -> IllegalArgumentException */
        @Test
        @DisplayName("null jako PdfTextExtractionResult rzuca IllegalArgumentException")
        void nullResultThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator().evaluate((PdfTextExtractionResult) null));
        }

        /** 11. acceptThreshold poza [0,1] -> IllegalArgumentException */
        @Test
        @DisplayName("acceptThreshold poza przedziałem [0,1] rzuca IllegalArgumentException")
        void invalidThresholdRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator(1.5, 5, 20, 0.05));
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator(-0.1, 5, 20, 0.05));
        }

        /** 11b. maxReplacementRatio poza [0,1] -> IllegalArgumentException */
        @Test
        @DisplayName("maxReplacementRatio poza przedziałem [0,1] rzuca IllegalArgumentException")
        void invalidReplacementRatioRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator(0.85, 5, 20, -0.1));
            assertThrows(IllegalArgumentException.class,
                    () -> new PageQualityEvaluator(0.85, 5, 20, 1.5));
        }
    }

    // =========================================================================
    // Sekcja 6 — monotoniczność replacementPenaltyFactor
    // =========================================================================

    @Nested
    @DisplayName("Monotoniczność karU+FFFD za liczbę znaków zastępczych")
    class MonotonicDecayTest {

        /**
         * 12. Ten sam długi tekst + rosnąca liczba U+FFFD -> score maleje
         *     monotonicznie. Porównujemy konkretne wartości (2 vs 10 replacement chars).
         */
        @Test
        @DisplayName("Rosnąca liczba U+FFFD spływa score monotonicznie")
        void replacementCharScoreDecreases() {
            // given — tekst bazowy (~166 znaków, ~31 słów, 100% alfanumeryczne)
            String base = (
                    "Komisja Europejska przyjmuje rozporządzenie wykonawcze w sprawie zasad "
                    + "implementacji dyrektywy dotyczącej ochrony danych osobowych w przedsiębiorstwach.");
            int baseLen = base.length();
            int baseWc = base.split("\\s+").length;

            // 2 znaki U+FFFD wplecione w środku
            String text2 = base.substring(0, baseLen / 2)
                    + "\uFFFD\uFFFD"
                    + base.substring(baseLen / 2);
            int len2 = text2.length();

            // 10 znaków U+FFFD
            String text10 = base.substring(0, baseLen / 4)
                    + "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"
                    + base.substring(baseLen / 4, baseLen / 2)
                    + "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"
                    + base.substring(baseLen / 2);
            int len10 = text10.length();

            var ev = new PageQualityEvaluator(0.85, 5, 20, 0.05);

            // when
            PageQualityScore s2 = ev.evaluate(
                    new PdfPageText(1, text2, len2, baseWc, true));
            PageQualityScore s10 = ev.evaluate(
                    new PdfPageText(1, text10, len10, baseWc, true));

            // then — więcej FFFD => niższy score
            assertAll(
                    () -> assertEquals(2, s2.replacementCharCount()),
                    () -> assertEquals(10, s10.replacementCharCount()),
                    () -> assertTrue(s2.score() > s10.score(),
                            "score z 2 FFFD (> s10.score())"),
                    () -> {
                        // obydwa ostrzeżenia
                        assertTrue(s2.warnings().get(0).contains("U+FFFD"));
                        assertTrue(s10.warnings().get(0).contains("U+FFFD"));
                    }
            );
        }
    }
}
