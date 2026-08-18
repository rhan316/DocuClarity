package org.dar316.docuclarity.service;

/**
 * Wyjątek runtime dla błędów uploadu dokumentu.
 */
public class DocumentUploadException extends RuntimeException {
    public DocumentUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
