package org.dar316.docuclarity.dto;

public record PdfPageText(
        int pageNum,
        String text,
        int charCount,
        int wordCount,
        boolean textPresent
) {
}
