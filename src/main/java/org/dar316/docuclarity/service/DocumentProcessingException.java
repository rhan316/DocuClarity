package org.dar316.docuclarity.service;

/**
 * Wyjątek runtime przetwarzania dokumentu w workerze.
 */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
