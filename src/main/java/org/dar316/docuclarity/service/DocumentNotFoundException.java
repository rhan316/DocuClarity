package org.dar316.docuclarity.service;

/**
 * Wyjątek runtime gdy dokument nie zostanie znaleziony.
 */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) {
        super(message);
    }
}
