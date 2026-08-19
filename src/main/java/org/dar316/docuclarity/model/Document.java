package org.dar316.docuclarity.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

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

    @Column("analysis_status")
    private String analysisStatus;

    @Column("analysis_model")
    private String analysisModel;

    @Column("analysis_completed_at")
    private Instant analysisCompletedAt;

    @Column("analysis_error_message")
    private String analysisErrorMessage;

    @Column("analysis_attempts")
    private int analysisAttempts;

    @Transient
    private boolean isNew = true;

    // --- Konstruktor dla nowych dokumentów (isNew=true) ---

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
        this.analysisStatus = "NOT_ANALYZED";
        this.isNew = true;
    }

    // --- Konstruktor dla Spring Data JDBC — odczyt z DB (isNew=false) ---
    // UWAGA: 14 parametrów — musi obejmować wszystkie kolumny z V1 + V2

    @PersistenceCreator
    public Document(UUID id,
                    String originalFilename,
                    String contentType,
                    long contentLength,
                    String storageKey,
                    String status,
                    int processingAttempts,
                    String errorMessage,
                    Instant createdAt,
                    Instant updatedAt,
                    String analysisStatus,
                    String analysisModel,
                    int analysisAttempts,
                    Instant analysisCompletedAt,
                    String analysisErrorMessage) {
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
        this.analysisStatus = analysisStatus;
        this.analysisModel = analysisModel;
        this.analysisAttempts = analysisAttempts;
        this.analysisCompletedAt = analysisCompletedAt;
        this.analysisErrorMessage = analysisErrorMessage;
        this.isNew = false;
    }

    public Document() {
    }

    // --- Gettery ---

    public int getAnalysisAttempts() {
        return analysisAttempts;
    }

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    public String getOriginalFilename() { return originalFilename; }

    public String getContentType() { return contentType; }

    public long getContentLength() { return contentLength; }

    public String getStorageKey() { return storageKey; }

    public String getStatus() { return status; }

    public DocumentStatus getStatusEnum() {
        return status != null ? DocumentStatus.fromCode(status) : null;
    }

    public int getProcessingAttempts() { return processingAttempts; }

    public String getErrorMessage() { return errorMessage; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public String getAnalysisStatus() { return analysisStatus; }

    public AnalysisStatus getAnalysisStatusEnum() {
        return analysisStatus != null ? AnalysisStatus.fromCode(analysisStatus) : null;
    }

    public String getAnalysisModel() { return analysisModel; }

    public Instant getAnalysisCompletedAt() { return analysisCompletedAt; }

    public String getAnalysisErrorMessage() { return analysisErrorMessage; }

    // --- Settery ---

    public void setId(UUID id) { this.id = id; }

    public void setStatus(String status) { this.status = status; }

    public void setStatus(DocumentStatus status) { this.status = status.code(); }

    public void setProcessingAttempts(int processingAttempts) {
        this.processingAttempts = processingAttempts;
    }

    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void setAnalysisStatus(String analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus.code();
    }

    public void setAnalysisModel(String analysisModel) { this.analysisModel = analysisModel; }

    public void setAnalysisCompletedAt(Instant analysisCompletedAt) {
        this.analysisCompletedAt = analysisCompletedAt;
    }

    public void setAnalysisErrorMessage(String analysisErrorMessage) {
        this.analysisErrorMessage = analysisErrorMessage;
    }

    public void setAnalysisAttempts(int analysisAttempts) {
        this.analysisAttempts = analysisAttempts;
    }
}
