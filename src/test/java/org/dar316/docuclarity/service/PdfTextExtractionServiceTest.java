package org.dar316.docuclarity.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.dar316.docuclarity.dto.PdfPageText;
import org.dar316.docuclarity.dto.PdfTextExtractionException;
import org.dar316.docuclarity.dto.PdfTextExtractionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe dla PdfTextExtractionService.
 *
 * Nie używają Springa ani Testcontainers — testują logikę ekstrakcji
 * w izolacji. Pliki PDF są generowane w pamięci przy użyciu PDFBox
 * (który jest już zależnością główną projektu).
 */
class PdfTextExtractionServiceTest {

    private final PdfTextExtractionService service = new PdfTextExtractionService();

    // --- Pomocnicze metody generowania PDF ---

    /**
     * Tworzy prosty PDF z jedną stroną zawierającą podany tekst.
     */
    private byte[] createSinglePagePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }

            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Tworzy PDF z wieloma stronami, każda z tekstem.
     */
    private byte[] createMultiPagePdf(String[] pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);

                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }

            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Tworzy pusty PDF (strona bez żadnej warstwy tekstowej).
     */
    private byte[] createBlankPagePdf() throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            doc.addPage(page);
            // Brak content stream z tekstem — pusta strona

            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Nested
    @DisplayName("Ekstrakcja z tablicy bajtów")
    class ExtractFromBytesTest {

        @Test
        @DisplayName("Pojedyncza strona z tekstem — poprawna ekstrakcja")
        void singlePageWithText() throws IOException {
            // given
            byte[] pdf = createSinglePagePdf("Witaj swiecie");

            // when
            PdfTextExtractionResult result = service.extractText(pdf);

            // then
            assertEquals(1, result.pageCount());
            assertEquals(1, result.pages().size());

            PdfPageText page = result.pages().get(0);
            assertEquals(1, page.pageNum());
            assertTrue(page.textPresent());
            assertTrue(page.text().contains("Witaj"));
            assertTrue(page.text().contains("swiecie"));
            assertEquals(page.text().length(), page.charCount());
            assertEquals(2, page.wordCount());
            assertEquals(result.combinedText(), page.text());
        }

        @Test
        @DisplayName("Wiele stron z tekstem — każda strona ekstrahowana osobno")
        void multiPageWithText() throws IOException {
            // given
            byte[] pdf = createMultiPagePdf(new String[]{
                    "Pierwsza strona",
                    "Druga strona",
                    "Trzecia strona"
            });

            // when
            PdfTextExtractionResult result = service.extractText(pdf);

            // then
            assertEquals(3, result.pageCount());
            assertEquals(3, result.pages().size());

            PdfPageText page1 = result.pages().get(0);
            PdfPageText page2 = result.pages().get(1);
            PdfPageText page3 = result.pages().get(2);

            assertEquals(1, page1.pageNum());
            assertEquals(2, page2.pageNum());
            assertEquals(3, page3.pageNum());

            assertTrue(page1.text().contains("Pierwsza"));
            assertTrue(page2.text().contains("Druga"));
            assertTrue(page3.text().contains("Trzecia"));

            assertTrue(result.combinedText().contains("Pierwsza"));
            assertTrue(result.combinedText().contains("Druga"));
            assertTrue(result.combinedText().contains("Trzecia"));
        }

        @Test
        @DisplayName("Pusta strona — textPresent == false, pusty tekst")
        void blankPage() throws IOException {
            // given
            byte[] pdf = createBlankPagePdf();

            // when
            PdfTextExtractionResult result = service.extractText(pdf);

            // then
            assertEquals(1, result.pageCount());
            PdfPageText page = result.pages().get(0);
            assertEquals(1, page.pageNum());
            assertFalse(page.textPresent());
            assertEquals("", page.text());
            assertEquals(0, page.charCount());
            assertEquals(0, page.wordCount());
            assertEquals("", result.combinedText());
        }

        @Test
        @DisplayName("Plik mieszany — strona z tekstem i pusta strona")
        void mixedPages() throws IOException {
            // given
            try (PDDocument doc = new PDDocument();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                // Strona 1: z tekstem
                PDPage page1 = new PDPage();
                doc.addPage(page1);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page1)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText("Tekst na stronie pierwszej");
                    stream.endText();
                }

                // Strona 2: pusta
                PDPage page2 = new PDPage();
                doc.addPage(page2);

                doc.save(baos);
                byte[] pdf = baos.toByteArray();

                // when
                PdfTextExtractionResult result = service.extractText(pdf);

                // then
                assertEquals(2, result.pageCount());
                assertTrue(result.pages().get(0).textPresent());
                assertFalse(result.pages().get(1).textPresent());
                assertTrue(result.combinedText().contains("Tekst na stronie pierwszej"));
            }
        }

        @Test
        @DisplayName("Null jako wejście — PdfTextExtractionException")
        void nullInput() {
            PdfTextExtractionException ex = assertThrows(
                    PdfTextExtractionException.class,
                    () -> service.extractText((byte[]) null)
            );
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("Pusta tablica bajtów — PdfTextExtractionException")
        void emptyBytes() {
            assertThrows(
                    PdfTextExtractionException.class,
                    () -> service.extractText(new byte[0])
            );
        }

        @Test
        @DisplayName("Niepoprawne dane (nie PDF) — PdfTextExtractionException")
        void invalidData() {
            byte[] notAPdf = "To nie jest plik PDF".getBytes();
            assertThrows(
                    PdfTextExtractionException.class,
                    () -> service.extractText(notAPdf)
            );
        }
    }

    @Nested
    @DisplayName("Ekstrakcja z InputStream")
    class ExtractFromInputStreamTest {

        @Test
        @DisplayName("Pojedyncza strona przez InputStream — poprawna ekstrakcja")
        void extractFromInputStream() throws IOException {
            // given
            byte[] pdf = createSinglePagePdf("Test przez strumien");
            java.io.InputStream is = new java.io.ByteArrayInputStream(pdf);

            // when
            PdfTextExtractionResult result = service.extractText(is);

            // then
            assertEquals(1, result.pageCount());
            assertTrue(result.pages().get(0).textPresent());
            assertTrue(result.combinedText().contains("Test"));
            assertTrue(result.combinedText().contains("strumien"));
        }

        @Test
        @DisplayName("InputStream z pliku na dysku — poprawna ekstrakcja")
        void extractFromFile(@TempDir Path tempDir) throws IOException {
            // given
            byte[] pdf = createSinglePagePdf("Plik z dysku");
            Path pdfFile = tempDir.resolve("test.pdf");
            Files.write(pdfFile, pdf);

            // when
            try (java.io.InputStream is = Files.newInputStream(pdfFile)) {
                PdfTextExtractionResult result = service.extractText(is);

                // then
                assertEquals(1, result.pageCount());
                assertTrue(result.combinedText().contains("Plik"));
                assertTrue(result.combinedText().contains("dysku"));
            }
        }

        @Test
        @DisplayName("Null InputStream — PdfTextExtractionException")
        void nullStream() {
            assertThrows(
                    PdfTextExtractionException.class,
                    () -> service.extractText((java.io.InputStream) null)
            );
        }
    }

    @Nested
    @DisplayName("Metryki tekstu")
    class TextMetricsTest {

        @Test
        @DisplayName("Liczba słów i znaków jest poprawna dla wielowyrazowego tekstu")
        void wordAndCharCount() throws IOException {
            // given — tekst z 4 słowami rozdzielonymi spacjami
            byte[] pdf = createSinglePagePdf("Ala ma kota czarnego");

            // when
            PdfTextExtractionResult result = service.extractText(pdf);

            // then
            PdfPageText page = result.pages().get(0);
            assertEquals(4, page.wordCount());
            // charCount == długość stripped tekstu
            assertEquals(page.text().length(), page.charCount());
        }

        @Test
        @DisplayName("Tekst z wieloma spacjami — liczenie słów ignoruje nadmiar")
        void multipleSpacesBetweenWords() throws IOException {
            // given — PDFBox zachowuje spacje, ale split po \s+ liczy poprawnie
            byte[] pdf = createSinglePagePdf("A  B   C");

            // when
            PdfTextExtractionResult result = service.extractText(pdf);

            // then
            PdfPageText page = result.pages().get(0);
            assertTrue(page.textPresent());
            // 3 tokeny po split \\s+
            assertEquals(3, page.wordCount());
        }
    }
}
