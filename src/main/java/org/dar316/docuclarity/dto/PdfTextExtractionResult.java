package org.dar316.docuclarity.dto;

import java.util.List;

public record PdfTextExtractionResult(
        int pageCount,
        List<PdfPageText> pages,
        String combinedText
) {
}
