package org.dar316.docuclarity.dto;

import java.util.List;

/**
 * Wynik ekstrakcji dla pojedynczej strony, zapisywany w MinIO
 * jako documents/{documentId}/pages/{nnn}/final.json.
 *
 * @param pageNum   numer strony (1-based)
 * @param engine    silnik, który dostarczył tekst: "PDFBOX" lub "OCR_TESS4J"
 * @param text      rozpoznany tekst strony
 * @param confidence średnia confidence OCR (null dla PDFBox, który nie mierzy confidence)
 * @param warnings  ostrzeżenia jakościowe z etapu routingu/ekstrakcji
 */
public record ExtractedPageResult(
        int pageNum,
        String engine,
        String text,
        Integer confidence,
        List<String> warnings
) {
}
