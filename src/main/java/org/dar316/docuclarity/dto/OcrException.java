package org.dar316.docuclarity.dto;

/**
 * Wyjątek runtime rzucany w przypadku błędów OCR (Tess4J).
 */
public class OcrException extends RuntimeException {

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }

    public OcrException(String message) {
        super(message);
    }
}
