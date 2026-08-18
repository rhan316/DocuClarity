package org.dar316.docuclarity.controller;

import org.dar316.docuclarity.dto.DocumentStatusResponse;
import org.dar316.docuclarity.dto.UploadResponse;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.service.DocumentNotFoundException;
import org.dar316.docuclarity.service.DocumentProgressService;
import org.dar316.docuclarity.service.DocumentService;
import org.dar316.docuclarity.service.DocumentUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for document operations and progress streaming.
 *
 * Endpoints:
 * - POST /api/documents/upload — Multipart PDF upload (201 created)
 * - GET  /api/documents/{id}   — Document metadata & processing (200 OK)
 * - GET /api/documents/{id}/progress - real-time progress update via SSE (text/event stream)
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final DocumentProgressService documentProgressService;

    public DocumentController(DocumentService documentService, DocumentProgressService documentProgressService) {
        this.documentService = documentService;
        this.documentProgressService = documentProgressService;
    }

    /**
     * Accepts a PDF file via multipart upload.
     *
     * @param file uploaded multipart file
     * @return 201 created with document metadata and Location header
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentUploadException("Plik jest pusty lub nie został przesłany", null);
        }

        try {
            Document document = documentService.uploadDocument(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream());

            UploadResponse response = new UploadResponse(
                    document.getId(),
                    document.getOriginalFilename(),
                    document.getContentType(),
                    document.getContentLength(),
                    document.getStatus(),
                    document.getCreatedAt());

            log.info("Upload zakończony: dokument {}", document.getId());
            return ResponseEntity
                    .created(URI.create("/api/documents/" + document.getId()))
                    .body(response);
        } catch (IOException e) {
            throw new DocumentUploadException(
                    "Błąd odczytu pliku: " + e.getMessage(), e);
        }
    }

    /**
     * Returns current document metadata and processing status.
     *
     * @param id Document UUID
     * @return 200 OK with Document status response
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentStatusResponse> getStatus(@PathVariable UUID id) {
        Document document = documentService.getDocument(id);
        DocumentStatusResponse response = new DocumentStatusResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getContentLength(),
                document.getStatus(),
                document.getProcessingAttempts(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt());
        return ResponseEntity.ok(response);
    }

    @GetMapping(
            path = "/{id}/progress",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamProgress(@PathVariable UUID id) {
        log.debug("Client connected to SSE progress stream for document {}", id);
        return documentProgressService.subscribe(id);
    }
}
