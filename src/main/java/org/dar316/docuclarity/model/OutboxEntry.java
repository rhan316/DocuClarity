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
 * Encja wpisu outbox — zdarzenie do publikacji na Redis Streams.
 *
 * Wzorzec Transactional Outbox: wpis i dokument są zapisywane w jednej transakcji.
 * Publisher odczytuje wpisy PENDING i publikuje je na Redis Streams.
 */
@Table("outbox")
public class OutboxEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("document_id")
    private UUID documentId;

    @Column("event_type")
    private String eventType;

    private String payload;

    private String status;

    private int attempts;

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private Instant createdAt;

    @Column("published_at")
    private Instant publishedAt;

    @Transient
    private boolean isNew = true;

    public OutboxEntry() {
    }

    // Konstruktor dla nowych wpisów (isNew=true)
    public OutboxEntry(UUID documentId, String eventType, String payload) {
        this.documentId = documentId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = "PENDING";
        this.attempts = 0;
        this.isNew = true;
    }

    // Konstruktor dla Spring Data JDBC — odczyt z DB (isNew=false)
    @PersistenceCreator
    public OutboxEntry(UUID id, UUID documentId, String eventType, String payload,
                       String status, int attempts, String errorMessage,
                       Instant createdAt, Instant publishedAt) {
        this.id = id;
        this.documentId = documentId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.isNew = false;
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

    public UUID getDocumentId() {
        return documentId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    // --- Settery ---

    public void setId(UUID id) {
        this.id = id;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
