package org.dar316.docuclarity.controller;

import org.dar316.docuclarity.dto.DocumentStatusResponse;
import org.dar316.docuclarity.dto.UploadResponse;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.service.DocumentNotFoundException;
import org.dar316.docuclarity.service.DocumentService;
import org.dar316.docuclarity.service.DocumentUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Kontroler REST dla operacji na dokumentach.
 *
 * Endpoints:
 * - POST /api/documents/upload — upload pliku (multipart/form-data)
 * - GET  /api/documents/{id}   — status dokumentu
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Przyjmuje plik PDF przez multipart upload.
     *
     * @param file plik multipart
     * @return 201 Created z metadanymi dokumentu
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
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            throw new DocumentUploadException(
                    "Błąd odczytu pliku: " + e.getMessage(), e);
        }
    }

    /**
     * Zwraca status dokumentu po ID.
     *
     * @param id UUID dokumentu
     * @return 200 OK z metadanymi i statusem
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
}
