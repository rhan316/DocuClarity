package org.dar316.docuclarity.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.dar316.docuclarity.dto.PdfPageText;
import org.dar316.docuclarity.dto.PdfTextExtractionException;
import org.dar316.docuclarity.dto.PdfTextExtractionResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serwis ekstrakcji tekstu z plików PDF przy użyciu Apache PDFBox 3.0.8.
 *
 * Ekstrakcja odbywa się strona po stronie. Dla każdej strony zwracane są
 * metadane: numer strony, tekst, liczba znaków, liczba słów oraz flaga
 * czy tekst w ogóle występuje. Wynikiem zbiorczym jest PdfTextExtractionResult
 * zawierający liczbę stron, listę wyników per strona oraz połączony tekst.
 *
 * Błędy ekstrakcji (uszkodzony PDF, brak warstwy tekstowej przy braku OCR,
 * zaszyfrowany PDF bez hasła) są propagowane jako PdfTextExtractionException.
 */
@Service
public class PdfTextExtractionService {

    /**
     * Ekstrahuje tekst z PDF dostarczonego jako tablica bajtów.
     *
     * @param pdfBytes zawartość pliku PDF
     * @return wynik ekstrakcji tekstu z metadanymi per strona
     * @throws PdfTextExtractionException gdy PDF jest uszkodzony, zaszyfrowany
     *         lub wystąpi błąd wejścia/wyjścia podczas ekstrakcji
     */
    public PdfTextExtractionResult extractText(byte[] pdfBytes) {
        if (Objects.isNull(pdfBytes) || pdfBytes.length == 0) {
            throw new PdfTextExtractionException(
                    "Pusty lub nullowy plik PDF", null);
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extractFromDocument(document);
        } catch (PdfTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfTextExtractionException(
                    "Błąd wczytywania pliku PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Ekstrahuje tekst z PDF dostarczonego jako InputStream.
     * Stream jest zamykany po zakończeniu ekstrakcji.
     *
     * @param inputStream strumień z zawartością pliku PDF
     * @return wynik ekstrakcji tekstu z metadanymi per strona
     * @throws PdfTextExtractionException gdy PDF jest uszkodzony, zaszyfrowany
     *         lub wystąpi błąd wejścia/wyjścia podczas ekstrakcji
     */
    public PdfTextExtractionResult extractText(InputStream inputStream) {
        if (Objects.isNull(inputStream)) {
            throw new PdfTextExtractionException(
                    "Nullowy strumień wejściowy", null);
        }

        try (inputStream) {
            byte[] pdfBytes = inputStream.readAllBytes();
            return extractText(pdfBytes);
        } catch (PdfTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfTextExtractionException(
                    "Błąd odczytu strumienia PDF: " + e.getMessage(), e);
        }
    }

    private PdfTextExtractionResult extractFromDocument(PDDocument document) {
        try {
            int pageCount = document.getNumberOfPages();
            var pages = new ArrayList<PdfPageText>(pageCount);
            var combinedText = new StringBuilder();

            var stripper = new PDFTextStripper();
            // Przetwarzanie strona po stronie — każdy osobny strip
            // daje tekst tylko jednej strony.
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PdfPageText pageText = extractPage(document, stripper, pageIndex);
                pages.add(pageText);
                if (pageText.textPresent()) {
                    if (combinedText.length() > 0) {
                        combinedText.append("\n\n");
                    }
                    combinedText.append(pageText.text());
                }
            }

            return new PdfTextExtractionResult(
                    pageCount,
                    pages,
                    combinedText.toString()
            );
        } catch (PdfTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfTextExtractionException(
                    "Błąd ekstrakcji tekstu: " + e.getMessage(), e);
        }
    }

    private PdfPageText extractPage(PDDocument document,
                                    PDFTextStripper stripper,
                                    int pageIndex) throws IOException {
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        String text = stripper.getText(document);
        if (text != null) {
            text = text.strip();
        }
        boolean textPresent = text != null && !text.isEmpty();
        int charCount = text != null ? text.length() : 0;
        int wordCount = textPresent ? countWords(text) : 0;

        return new PdfPageText(
                pageIndex + 1,
                text != null ? text : "",
                charCount,
                wordCount,
                textPresent
        );
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // Dzielimy po białych znakach; puste tokeny odfiltrowujemy.
        String[] tokens = text.trim().split("\\s+");
        return tokens.length;
    }
}
