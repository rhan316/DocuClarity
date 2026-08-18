package org.dar316.docuclarity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.OutboxEntry;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.dar316.docuclarity.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Serwis zarządzania dokumentami — orkiestracja uploadu.
 *
 * Przepływ:
 * 1. Upload pliku do MinIO (storageKey = documents/{uuid}/source)
 * 2. W jednej transakcji DB: zapis dokumentu (status UPLOADED) + wpis outbox (DOCUMENT_UPLOADED)
 * 3. Jeśli DB fails → kompensacja: usunięcie pliku z MinIO
 *
 * UUID dokumentu jest generowane przed uploadem — storageKey jest znany przed zapisem do DB.
 * Pozwala to na atomowy zapis: plik w MinIO + rekord w DB wskazujący na ten sam klucz.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String STORAGE_KEY_PREFIX = "documents/";
    private static final String STORAGE_KEY_SUFFIX = "/source";
    private static final String EVENT_DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";

    private final MinioStorageService minioStorageService;
    private final DocumentRepository documentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DocumentService(MinioStorageService minioStorageService,
                           DocumentRepository documentRepository,
                           OutboxRepository outboxRepository,
                           @Qualifier("appObjectMapper") ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate) {
        this.minioStorageService = minioStorageService;
        this.documentRepository = documentRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Przyjmuje plik, zapisuje go w MinIO i tworzy rekord dokumentu + wpis outbox.
     *
     * @param originalFilename nazwa oryginalna pliku
     * @param contentType      typ MIME pliku
     * @param contentLength    długość pliku w bajtach
     * @param inputStream      strumień z zawartością pliku
     * @return zapisany dokument z wygenerowanym ID
     * @throws DocumentUploadException gdy upload do MinIO lub zapis do DB się nie powiedzie
     */
    public Document uploadDocument(String originalFilename,
                                   String contentType,
                                   long contentLength,
                                   InputStream inputStream) {
        validateInput(originalFilename, contentType, contentLength, inputStream);

        UUID documentId = UUID.randomUUID();
        String storageKey = buildStorageKey(documentId);

        // Krok 1: Upload do MinIO (poza transakcją DB)
        minioStorageService.uploadFile(storageKey, inputStream, contentType, contentLength);
        log.info("Plik uploaded do MinIO: {}, dokument: {}", storageKey, documentId);

        // Krok 2: Zapis do DB w transakcji (dokument + outbox)
        // TransactionTemplate zapewnia poprawną transakcję — self-invocation
        // z @Transactional nie działa przez Spring AOP proxy
        try {
            return transactionTemplate.execute(status ->
                    saveDocumentWithOutbox(documentId, originalFilename, contentType,
                            contentLength, storageKey));
        } catch (Exception e) {
            // Kompensacja: usuń plik z MinIO, bo DB zapis się nie powiódł
            log.error("Błąd zapisu dokumentu do DB, kompensacja — usuwam plik z MinIO: {}",
                    storageKey, e);
            minioStorageService.deleteFile(storageKey);
            throw new DocumentUploadException(
                    "Błąd zapisu dokumentu do bazy danych: " + e.getMessage(), e);
        }
    }

    private Document saveDocumentWithOutbox(UUID documentId,
                                              String originalFilename,
                                              String contentType,
                                              long contentLength,
                                              String storageKey) {
        Document document = new Document(originalFilename, contentType, contentLength, storageKey);
        document.setId(documentId);
        document = documentRepository.save(document);

        OutboxEntry outboxEntry = new OutboxEntry(
                document.getId(),
                EVENT_DOCUMENT_UPLOADED,
                buildOutboxPayload(document));
        outboxRepository.save(outboxEntry);

        log.info("Zapisano dokument {} i wpis outbox {}", document.getId(), outboxEntry.getId());
        return document;
    }

    private String buildOutboxPayload(Document document) {
        try {
            Map<String, Object> payload = Map.of(
                    "documentId", document.getId().toString(),
                    "storageKey", document.getStorageKey(),
                    "contentType", document.getContentType(),
                    "contentLength", document.getContentLength(),
                    "originalFilename", document.getOriginalFilename()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DocumentUploadException(
                    "Błąd serializacji payloadu outbox: " + e.getMessage(), e);
        }
    }

    private String buildStorageKey(UUID documentId) {
        return STORAGE_KEY_PREFIX + documentId + STORAGE_KEY_SUFFIX;
    }

    private void validateInput(String originalFilename,
                               String contentType,
                               long contentLength,
                               InputStream inputStream) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new DocumentUploadException("Nazwa pliku nie może być pusta", null);
        }
        if (contentType == null || contentType.isBlank()) {
            throw new DocumentUploadException("Typ zawartości nie może być pusty", null);
        }
        if (contentLength <= 0) {
            throw new DocumentUploadException(
                    "Długość pliku musi być dodatnia: " + contentLength, null);
        }
        if (Objects.isNull(inputStream)) {
            throw new DocumentUploadException("Strumień wejściowy nie może być null", null);
        }
    }

    /**
     * Pobiera dokument po ID.
     *
     * @param id UUID dokumentu
     * @return dokument
     * @throws DocumentNotFoundException gdy dokument nie istnieje
     */
    public Document getDocument(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "Nie znaleziono dokumentu: " + id));
    }
}
