package org.dar316.docuclarity.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja dokumentu — rekord przesłanego pliku z metadanymi i statusem przetwarzania.
 *
 * Odpowiada tabeli documents w PostgreSQL. Mapowanie przez Spring Data JDBC.
 * Implementuje Persistable — @PersistenceConstructor oznacza konstruktor
 * wywoływany przy odczycie z DB (isNew=false). Domyślny konstruktor ustawia
 * isNew=true, pozwalając na ustawienie ID przed save bez konwersji na UPDATE.
 */
@Table("documents")
public class Document implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("original_filename")
    private String originalFilename;

    @Column("content_type")
    private String contentType;

    @Column("content_length")
    private long contentLength;

    @Column("storage_key")
    private String storageKey;

    private String status;

    @Column("processing_attempts")
    private int processingAttempts;

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    // Konstruktor dla nowych dokumentów (isNew=true)
    public Document(String originalFilename,
                    String contentType,
                    long contentLength,
                    String storageKey) {
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.storageKey = storageKey;
        this.status = "UPLOADED";
        this.processingAttempts = 0;
        this.isNew = true;
    }

    // Konstruktor dla Spring Data JDBC — odczyt z DB (isNew=false)
    @PersistenceCreator
    public Document(UUID id, String originalFilename, String contentType,
                    long contentLength, String storageKey, String status,
                    int processingAttempts, String errorMessage,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.storageKey = storageKey;
        this.status = status;
        this.processingAttempts = processingAttempts;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isNew = false;
    }

    public Document() {
    }

    // --- Gettery ---

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getStatus() {
        return status;
    }

    /** Zwraca status jako enum (konwersja z String przechowywanego w DB). */
    public DocumentStatus getStatusEnum() {
        return status != null ? DocumentStatus.fromCode(status) : null;
    }

    public int getProcessingAttempts() {
        return processingAttempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // --- Settery (używane przez Spring Data JDBC i logikę biznesową) ---

    public void setId(UUID id) {
        this.id = id;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** Ustawia status z enumu (zapis jako kod tekstowy zgodny z CHECK constraint). */
    public void setStatus(DocumentStatus status) {
        this.status = status.code();
    }

    public void setProcessingAttempts(int processingAttempts) {
        this.processingAttempts = processingAttempts;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
