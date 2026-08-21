package org.dar316.docuclarity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dar316.docuclarity.dto.AnalysisRequest;
import org.dar316.docuclarity.dto.DocumentProgressEvent;
import org.dar316.docuclarity.model.AnalysisStatus;
import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.DocumentStatus;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AnalysisService}.
 *
 * <p>Follows the same pattern as {@link DocumentProcessingServiceTest}: Mockito
 * without Spring context or Testcontainers. All dependencies are mocked.
 * {@link TransactionTemplate#executeWithoutResult} is stubbed to execute the
 * {@link Consumer} synchronously.</p>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    // ========================================================================
    // Mocks
    // ========================================================================

    DocumentRepository documentRepository;
    RedisTemplate<String, String> redisTemplate;
    StreamOperations<String, Object, Object> streamOperations;
    ObjectMapper objectMapper;
    TransactionTemplate transactionTemplate;
    DocumentProgressService documentProgressService;

    private AnalysisService sut;

    private static final String REQUEST_STREAM_KEY = "docuclarity.analysis.requested";
    private static final String EXTRACTED_KEY_TEMPLATE = "documents/%s/result.json";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        documentRepository = Mockito.mock(DocumentRepository.class);
        redisTemplate = Mockito.mock(RedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        objectMapper = Mockito.mock(ObjectMapper.class);
        transactionTemplate = Mockito.mock(TransactionTemplate.class);
        documentProgressService = Mockito.mock(DocumentProgressService.class);

        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        sut = new AnalysisService(
                documentRepository,
                redisTemplate,
                objectMapper,
                transactionTemplate,
                documentProgressService,
                REQUEST_STREAM_KEY,
                EXTRACTED_KEY_TEMPLATE
        );
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void initTxMocks() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any(Consumer.class));
    }

    private Document prepareDocument(UUID id,
                                     DocumentStatus docStatus,
                                     AnalysisStatus analysisStatus) {
        Document doc = new Document("test.pdf", "application/pdf", 2048L,
                "documents/" + id + "/source");
        doc.setId(id);
        doc.setStatus(docStatus);
        doc.setAnalysisStatus(analysisStatus);
        lenient().when(documentRepository.findById(eq(id)))
                .thenReturn(Optional.of(doc));
        lenient().when(documentRepository.save(any(Document.class)))
                .thenAnswer(i -> i.getArgument(0));
        return doc;
    }

    // ========================================================================
    // Test 1: Happy path
    // ========================================================================

    @Nested
    @DisplayName("Happy path — COMPLETED document triggers analysis request")
    class HappyPathTest {

        @Test
        @DisplayName("requestAnalysis sets ANALYSIS_QUEUED and publishes to Redis stream")
        void requestAnalysis_completedDocument_setsQueuedAndPublishes() throws Exception {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.NOT_ANALYZED);

            when(objectMapper.writeValueAsString(any()))
                    .thenReturn("{\"documentId\":\"" + docId + "\"}");
            initTxMocks();

            sut.requestAnalysis(docId);

            // DB: ANALYSIS_QUEUED
            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(docCaptor.capture());
            assertEquals("ANALYSIS_QUEUED", docCaptor.getValue().getAnalysisStatus());
            assertNull(docCaptor.getValue().getAnalysisErrorMessage());

            // Redis: publish called
            verify(streamOperations).add(any());

            // Payload: correct documentId + storage key
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(objectMapper).writeValueAsString(payloadCaptor.capture());
            assertInstanceOf(AnalysisRequest.class, payloadCaptor.getValue());
            AnalysisRequest request = (AnalysisRequest) payloadCaptor.getValue();
            assertEquals(docId, request.documentId());
            assertEquals(
                    String.format(EXTRACTED_KEY_TEMPLATE, docId),
                    request.extractedTextStorageKey());
        }

        @Test
        @DisplayName("Auto-trigger sends SSE with correct documentId and stage")
        void requestAnalysis_autoTrigger_sendsSse() throws Exception {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.NOT_ANALYZED);

            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            initTxMocks();

            sut.requestAnalysis(docId);

            ArgumentCaptor<DocumentProgressEvent> eventCaptor =
                    ArgumentCaptor.forClass(DocumentProgressEvent.class);
            verify(documentProgressService).notifyProgress(eventCaptor.capture());
            DocumentProgressEvent event = eventCaptor.getValue();
            assertEquals(docId, event.documentId());
            assertEquals("ANALYSIS_QUEUED", event.stage());
            assertNotNull(event.message());
        }

        @Test
        @DisplayName("Manual trigger requestAnalysis(id, false) sends SSE")
        void requestAnalysis_manualTrigger_sendsSse() throws Exception {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.NOT_ANALYZED);

            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            initTxMocks();

            sut.requestAnalysis(docId, false);

            ArgumentCaptor<DocumentProgressEvent> eventCaptor =
                    ArgumentCaptor.forClass(DocumentProgressEvent.class);
            verify(documentProgressService).notifyProgress(eventCaptor.capture());
            DocumentProgressEvent event = eventCaptor.getValue();
            assertEquals(docId, event.documentId());
            assertEquals("ANALYSIS_QUEUED", event.stage());
            assertNotNull(event.message());
        }
    }

    // ========================================================================
    // Test 2: Reject non-COMPLETED documents
    // ========================================================================

    @Nested
    @DisplayName("Non-COMPLETED document — rejection")
    class NonCompletedStatusTest {

        @Test
        @DisplayName("UPLOADED → DocumentProcessingException, no publish")
        void requestAnalysis_uploadedStatus_throws() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.UPLOADED, AnalysisStatus.NOT_ANALYZED);
            initTxMocks();

            assertThrows(DocumentProcessingException.class, () -> sut.requestAnalysis(docId));
            verify(streamOperations, never()).add(any());
            verify(documentProgressService, never()).notifyProgress(any());
        }

        @Test
        @DisplayName("PROCESSING → DocumentProcessingException, no publish")
        void requestAnalysis_processingStatus_throws() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.PROCESSING, AnalysisStatus.NOT_ANALYZED);
            initTxMocks();

            assertThrows(DocumentProcessingException.class, () -> sut.requestAnalysis(docId));
            verify(streamOperations, never()).add(any());
        }

        @Test
        @DisplayName("FAILED → DocumentProcessingException, no publish")
        void requestAnalysis_failedStatus_throws() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.FAILED, AnalysisStatus.NOT_ANALYZED);
            initTxMocks();

            assertThrows(DocumentProcessingException.class, () -> sut.requestAnalysis(docId));
            verify(streamOperations, never()).add(any());
        }

        @Test
        @DisplayName("MANUAL_REVIEW → DocumentProcessingException, no publish")
        void requestAnalysis_manualReviewStatus_throws() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.MANUAL_REVIEW, AnalysisStatus.NOT_ANALYZED);
            initTxMocks();

            assertThrows(DocumentProcessingException.class, () -> sut.requestAnalysis(docId));
            verify(streamOperations, never()).add(any());
        }
    }

    // ========================================================================
    // Test 3: Skip if already ANALYSIS_QUEUED or ANALYZING
    // ========================================================================

    @Nested
    @DisplayName("Already in-progress — skip")
    class AlreadyInProgressTest {

        @Test
        @DisplayName("ANALYSIS_QUEUED → no save, no publish, no SSE")
        void requestAnalysis_alreadyQueued_skips() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.ANALYSIS_QUEUED);
            initTxMocks();

            sut.requestAnalysis(docId);

            verify(documentRepository, never()).save(any());
            verify(streamOperations, never()).add(any());
            verify(documentProgressService, never()).notifyProgress(any());
        }

        @Test
        @DisplayName("ANALYZING → no save, no publish, no SSE")
        void requestAnalysis_alreadyAnalyzing_skips() {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.ANALYZING);
            initTxMocks();

            sut.requestAnalysis(docId);

            verify(documentRepository, never()).save(any());
            verify(streamOperations, never()).add(any());
            verify(documentProgressService, never()).notifyProgress(any());
        }
    }

    // ========================================================================
    // Test 4: JsonProcessingException rollback
    // ========================================================================

    @Nested
    @DisplayName("JSON serialization failure — rollback")
    class JsonSerializationFailureTest {

        @Test
        @DisplayName("JsonProcessingException → rollback to NOT_ANALYZED + AnalysisException")
        void requestAnalysis_jsonFails_rollsBackAndThrows() throws Exception {
            UUID docId = UUID.randomUUID();
            var doc = prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.NOT_ANALYZED);

            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("Serialization failed") {});
            initTxMocks();

            assertThrows(AnalysisException.class, () -> sut.requestAnalysis(docId));
            verify(documentRepository, times(2)).save(any());

            assertEquals("NOT_ANALYZED", doc.getAnalysisStatus(),
                    "Document should be rolled back to NOT_ANALYZED after serialization failure");

            verify(documentProgressService, never()).notifyProgress(any());
        }
    }

    // ========================================================================
    // Test 5: Document not found
    // ========================================================================

    @Nested
    @DisplayName("Document not found")
    class DocumentNotFoundTest {

        @Test
        @DisplayName("Non-existent documentId → DocumentNotFoundException")
        void requestAnalysis_documentNotFound_throws() {
            UUID docId = UUID.randomUUID();
            when(documentRepository.findById(docId)).thenReturn(Optional.empty());
            initTxMocks();

            assertThrows(DocumentNotFoundException.class, () -> sut.requestAnalysis(docId));
            verify(streamOperations, never()).add(any());
            verify(documentProgressService, never()).notifyProgress(any());
        }
    }

    // ========================================================================
    // Test 6: Re-analysis from terminal states
    // ========================================================================

    @Nested
    @DisplayName("Re-analysis from terminal states — allowed")
    class ReanalysisTest {

        @Test
        @DisplayName("ANALYZED → re-queues")
        void requestAnalysis_alreadyAnalyzed_requeues() throws Exception {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.ANALYZED);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            initTxMocks();

            sut.requestAnalysis(docId);

            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(docCaptor.capture());
            assertEquals("ANALYSIS_QUEUED", docCaptor.getValue().getAnalysisStatus());
            verify(streamOperations).add(any());
        }

        @Test
        @DisplayName("ANALYSIS_FAILED → re-queues")
        void requestAnalysis_analysisFailed_requeues() throws Exception {
            UUID docId = UUID.randomUUID();
            prepareDocument(docId, DocumentStatus.COMPLETED, AnalysisStatus.ANALYSIS_FAILED);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            initTxMocks();

            sut.requestAnalysis(docId);

            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(docCaptor.capture());
            assertEquals("ANALYSIS_QUEUED", docCaptor.getValue().getAnalysisStatus());
            verify(streamOperations).add(any());
        }
    }
}
