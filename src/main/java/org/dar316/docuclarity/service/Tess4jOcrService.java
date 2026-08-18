package org.dar316.docuclarity.service;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.dar316.docuclarity.dto.OcrException;
import org.dar316.docuclarity.dto.OcrPageResult;
import org.dar316.docuclarity.dto.OcrWord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serwis OCR przy użyciu Tess4J (Tesseract OCR 5.x).
 *
 * Odpowiedzialność:
 * 1. Renderowanie strony PDF do obrazu rastrowego (konfigurowalne DPI, domyślnie 300).
 * 2. Uruchomienie Tesseract OCR z per-word confidence.
 * 3. Zwrócenie OcrPageResult z tekstem, listą słów i średnią confidence.
 *
 * Routing jakościowy (kiedy używać OCR) jest realizowany przez wyższą warstwę
 * (PageQualityEvaluator / pipeline), nie przez ten serwis.
 */
@Service
public class Tess4jOcrService {

    private final int renderDpi;
    private final String tessdataPath;
    private final String language;
    private final int pageSegMode;

    /**
     * @param renderDpi     DPI renderowania strony PDF do obrazu (domyślnie 300)
     * @param tessdataPath  ścieżka do katalogu tessdata (domyślnie /usr/share/tessdata)
     * @param language      kod języka Tesseract (domyślnie eng)
     * @param pageSegMode   tryb segmentacji strony (domyślnie 1 = automatic page segmentation with OSD)
     */
    public Tess4jOcrService(
            @Value("${docuclarity.ocr.render-dpi:300}") int renderDpi,
            @Value("${docuclarity.ocr.tessdata-path:/usr/share/tessdata}") String tessdataPath,
            @Value("${docuclarity.ocr.language:eng}") String language,
            @Value("${docuclarity.ocr.page-seg-mode:1}") int pageSegMode
    ) {
        this.renderDpi = renderDpi;
        this.tessdataPath = tessdataPath;
        this.language = language;
        this.pageSegMode = pageSegMode;
    }

    // --- Public API ---

    /**
     * Renderuje wskazaną stronę PDF do obrazu i uruchamia OCR.
     *
     * @param pdfBytes  zawartość pliku PDF
     * @param pageIndex indeks strony (0-based)
     * @return wynik OCR dla strony
     * @throws OcrException gdy PDF jest uszkodzony lub OCR zakończy się błędem
     */
    public OcrPageResult ocrPage(byte[] pdfBytes, int pageIndex) {
        if (Objects.isNull(pdfBytes) || pdfBytes.length == 0) {
            throw new OcrException("Pusty lub nullowy plik PDF");
        }
        if (pageIndex < 0) {
            throw new OcrException("Indeks strony nie może być ujemny: " + pageIndex);
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (pageIndex >= document.getNumberOfPages()) {
                throw new OcrException(
                        "Strona " + pageIndex + " nie istnieje (dokument ma "
                                + document.getNumberOfPages() + " stron)");
            }
            BufferedImage image = renderPage(document, pageIndex);
            return performOcr(image, pageIndex + 1);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("Błąd OCR strony " + pageIndex + ": " + e.getMessage(), e);
        }
    }

    /**
     * Renderuje wskazaną stronę PDF (z InputStream) do obrazu i uruchamia OCR.
     * Stream jest zamykany po zakończeniu.
     *
     * @param inputStream strumień z zawartością PDF
     * @param pageIndex   indeks strony (0-based)
     * @return wynik OCR dla strony
     * @throws OcrException gdy PDF jest uszkodzony lub OCR zakończy się błędem
     */
    public OcrPageResult ocrPage(InputStream inputStream, int pageIndex) {
        if (Objects.isNull(inputStream)) {
            throw new OcrException("Nullowy strumień wejściowy");
        }
        try (inputStream) {
            byte[] pdfBytes = inputStream.readAllBytes();
            return ocrPage(pdfBytes, pageIndex);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException(
                    "Błąd odczytu strumienia PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Uruchamia OCR bezpośrednio na obrazie (np. z renderowania PDF lub z pliku).
     *
     * @param image    obraz do OCR
     * @param pageNum  numer strony (1-based) do metadanych wyniku
     * @return wynik OCR
     * @throws OcrException gdy OCR zakończy się błędem
     */
    public OcrPageResult ocrImage(BufferedImage image, int pageNum) {
        if (Objects.isNull(image)) {
            throw new OcrException("Nullowy obraz wejściowy");
        }
        try {
            return performOcr(image, pageNum);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException(
                    "Błąd OCR obrazu: " + e.getMessage(), e);
        }
    }

    // --- Internal ---

    private BufferedImage renderPage(PDDocument document, int pageIndex) throws Exception {
        PDFRenderer renderer = new PDFRenderer(document);
        return renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
    }

    private OcrPageResult performOcr(BufferedImage image, int pageNum) throws Exception {
        // PDFBox renderImageWithDPI zwraca TYPE_INT_RGB — obsługiwany przez Tess4J
        ITesseract tess = createTesseract();

        // Pełny tekst strony
        String fullText = tess.doOCR(image);
        if (fullText != null) {
            fullText = fullText.strip();
        }
        boolean textPresent = fullText != null && !fullText.isEmpty();

        // Per-word confidence
        List<Word> tWords = tess.getWords(image, 1); // 1 = PageIteratorLevel.WORD
        List<OcrWord> words = new ArrayList<>(tWords.size());
        int totalConfidence = 0;
        for (Word w : tWords) {
            String wText = w.getText();
            if (wText == null || wText.isBlank()) {
                continue;
            }
            int conf = (int) Math.round(w.getConfidence());
            java.awt.Rectangle rect = w.getBoundingBox();
            int[] bbox = rect != null
                    ? new int[]{rect.x, rect.y, rect.width, rect.height}
                    : null;
            words.add(new OcrWord(wText.strip(), conf, bbox));
            totalConfidence += conf;
        }
        int meanConfidence = words.isEmpty() ? 0 : totalConfidence / words.size();

        return new OcrPageResult(
                pageNum,
                fullText != null ? fullText : "",
                words,
                meanConfidence,
                textPresent
        );
    }

    private ITesseract createTesseract() {
        ITesseract tess = new Tesseract();
        tess.setDatapath(tessdataPath);
        tess.setLanguage(language);
        tess.setPageSegMode(pageSegMode);
        return tess;
    }
}
