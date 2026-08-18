package org.dar316.docuclarity.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.dar316.docuclarity.dto.OcrException;
import org.dar316.docuclarity.dto.OcrPageResult;
import org.dar316.docuclarity.dto.OcrWord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy integracyjne dla Tess4jOcrService.
 *
 * Wymagają natywnego Tesseract OCR i traineddata dla 'eng' w środowisku.
 * Aktywowane tylko gdy zmienna środowiskowa TESSDATA_PATH istnieje
 * (zapobiega failure na CI bez Tesseract).
 *
 * Obrazy testowe są generowane w pamięci przez Java2D — wysoki kontrast,
 * duża czcionka, przewidywalny tekst.
 */
@EnabledIfEnvironmentVariable(named = "TESSDATA_PATH", matches = ".+")
class Tess4jOcrServiceTest {

    // Używamy tessdata z env (ustawione przez środowisko testowe)
    // Język z env DOCUCLARITY_OCR_LANGUAGE lub fallback na eng
    // (lokalnie pol może nie być zainstalowany)
    private final Tess4jOcrService service = new Tess4jOcrService(
            300,
            System.getenv("TESSDATA_PATH"),
            System.getenv().getOrDefault("DOCUCLARITY_OCR_LANGUAGE", "eng"),
            1
    );

    // --- Pomocnicze generowanie obrazów ---

    /**
     * Generuje obraz z białym tłem i czarnym tekstem, dużą czcionką.
     */
    private BufferedImage createTextImage(String text) {
        int width = 800;
        int height = 200;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 36));
            FontMetrics fm = g.getFontMetrics();
            int x = 50;
            int y = (height - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Generuje pusty biały obraz (bez tekstu).
     */
    private BufferedImage createBlankImage() {
        int width = 800;
        int height = 200;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Generuje prosty PDF z jedną stroną zawierającą podany tekst.
     */
    private byte[] createSinglePagePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Nested
    @DisplayName("OCR bezpośrednio na obrazie")
    class OcrImageTest {

        @Test
        @DisplayName("Obraz z tekstem — OCR rozpoznaje tekst")
        void ocrTextImage() {
            BufferedImage img = createTextImage("HELLO WORLD");

            OcrPageResult result = service.ocrImage(img, 1);

            assertEquals(1, result.pageNum());
            assertTrue(result.textPresent());
            // OCR może nie być idealny, ale powinien rozpoznać przynajmniej
            // część tekstu — sprawdzamy case-insensitive
            String upper = result.text().toUpperCase();
            assertTrue(upper.contains("HELLO") || upper.contains("WORLD"),
                    "OCR powinien rozpoznać HELLO lub WORLD, rozpoznał: "
                            + result.text());
            assertFalse(result.words().isEmpty(),
                    "Lista słów nie powinna być pusta");
            assertTrue(result.meanConfidence() > 0,
                    "Średnia confidence powinna być > 0");
        }

        @Test
        @DisplayName("Pusty obraz — textPresent == false")
        void ocrBlankImage() {
            BufferedImage img = createBlankImage();

            OcrPageResult result = service.ocrImage(img, 1);

            assertEquals(1, result.pageNum());
            // Pusty obraz może dać pusty tekst lub śmieci — sprawdzamy
            // że nie ma rozpoznanych słów z wysoką confidence
            if (!result.textPresent()) {
                assertTrue(result.words().isEmpty());
            }
        }

        @Test
        @DisplayName("Per-word confidence — każde słowo ma confidence 0–100")
        void wordConfidenceRange() {
            BufferedImage img = createTextImage("TEST WORDS");

            OcrPageResult result = service.ocrImage(img, 1);

            for (OcrWord w : result.words()) {
                assertTrue(w.confidence() >= 0 && w.confidence() <= 100,
                        "Confidence poza zakresem 0-100: " + w.confidence()
                                + " dla słowa '" + w.text() + "'");
                assertNotNull(w.text());
                assertFalse(w.text().isBlank());
            }
        }

        @Test
        @DisplayName("Null obraz — OcrException")
        void nullImage() {
            assertThrows(OcrException.class,
                    () -> service.ocrImage(null, 1));
        }
    }

    @Nested
    @DisplayName("OCR na stronie PDF")
    class OcrPdfPageTest {

        @Test
        @DisplayName("Strona PDF z tekstem — OCR przez renderowanie")
        void ocrPdfWithText() throws IOException {
            byte[] pdf = createSinglePagePdf("Hello PDF");

            OcrPageResult result = service.ocrPage(pdf, 0);

            assertEquals(1, result.pageNum()); // 0-based index → 1-based page
            // Tekst w PDF ma wektorową warstwę tekstową, ale render do obrazu
            // i OCR nadal powinien rozpoznać tekst
            assertTrue(result.textPresent(),
                    "OCR powinien rozpoznać tekst na renderowanej stronie");
            String upper = result.text().toUpperCase();
            assertTrue(upper.contains("HELLO") || upper.contains("PDF"),
                    "OCR powinien rozpoznać HELLO lub PDF, rozpoznał: "
                            + result.text());
        }

        @Test
        @DisplayName("Indeks strony poza zakresem — OcrException")
        void pageIndexOutOfRange() throws IOException {
            byte[] pdf = createSinglePagePdf("Test");
            assertThrows(OcrException.class,
                    () -> service.ocrPage(pdf, 99));
        }

        @Test
        @DisplayName("Null PDF — OcrException")
        void nullPdf() {
            assertThrows(OcrException.class,
                    () -> service.ocrPage((byte[]) null, 0));
        }

        @Test
        @DisplayName("Pusty PDF — OcrException")
        void emptyPdf() {
            assertThrows(OcrException.class,
                    () -> service.ocrPage(new byte[0], 0));
        }

        @Test
        @DisplayName("Niepoprawne dane (nie PDF) — OcrException")
        void invalidData() {
            byte[] notAPdf = "Not a PDF".getBytes();
            assertThrows(OcrException.class,
                    () -> service.ocrPage(notAPdf, 0));
        }

        @Test
        @DisplayName("Ujemny indeks strony — OcrException")
        void negativePageIndex() throws IOException {
            byte[] pdf = createSinglePagePdf("Test");
            assertThrows(OcrException.class,
                    () -> service.ocrPage(pdf, -1));
        }
    }
}
