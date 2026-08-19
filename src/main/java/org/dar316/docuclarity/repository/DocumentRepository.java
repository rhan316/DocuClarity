package org.dar316.docuclarity.repository;

import org.dar316.docuclarity.model.Document;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentRepository extends CrudRepository<Document, UUID> {

    /**
     * Atomically claims a document for processing.
     *
     * Succeeds only if the current status is UPLOADED - the UPDATE and
     * the status check happen in a single SQL statement,
     * so two concurrent transactions cannot both claim the same document.
     * @param id
     * @return 1 if claimed, 0 if the document was not in UPLOADED status
     * (or does not exist)
     */
    @Modifying
    @Query("""
        UPDATE
            documents
        SET
            status = 'PROCESSING', processing_attempts = processing_attempts + 1,
            updated_at = now()
        WHERE
            id = :id AND status = 'UPLOADED'
    """)
    int claimForProcessing(@Param("id")  UUID id);

    @Modifying
    @Query("""
        UPDATE
            documents
        SET
            status = 'UPLOADED'
        WHERE
            status = 'PROCESSING'
    """)
    int resetStuckProcessing();

    /**
     * Atomically claims a document for LLM analysis.
     * Succeeds only if analysis_status is ANALYSIS_QUEUED.
     * Increments analysis_attempts atomically.
     *
     * @param id
     * @return
     */
    @Modifying
    @Query("""
        UPDATE
            documents
        SET
            analysis_status = 'ANALYZING',
            analysis_attempts = analysis_attempts + 1,
            updated_at = now()
        WHERE
            id = :id AND analysis_status = 'ANALYSIS_QUEUED'
    """)
    int claimForAnalysis(@Param("id")  UUID id);

    @Modifying
    @Query("""
        UPDATE
            documents
        SET
            analysis_status = 'ANALYSIS_QUEUED'
        WHERE
            analysis_status = 'ANALYZING'
    """)
    int resetStuckAnalysis();
}
