package org.dar316.docuclarity.dto;

/**
 * Reprezentuje pojedyncze słowo rozpoznane przez OCR (Tesseract).
 *
 * @param text      rozpoznany tekst słowa
 * @param confidence pewność rozpoznania w skali 0–100 (Tesseract confidence)
 * @param bbox      bounding box słowa na stronie w pikselach [x, y, width, height];
 *                  może być null gdy Tesseract nie dostarczył pozycji
 */
public record OcrWord(
        String text,
        int confidence,
        int[] bbox
) {
}
