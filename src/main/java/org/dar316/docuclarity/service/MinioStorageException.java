package org.dar316.docuclarity.service;

/**
 * Wyjątek runtime dla błędów operacji MinIO.
 */
public class MinioStorageException extends RuntimeException {
    public MinioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
