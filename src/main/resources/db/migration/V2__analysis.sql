
ALTER TABLE documents
    ADD COLUMN analysis_status TEXT NOT NULL DEFAULT 'NOT_ANALYZED'
        CHECK (analysis_status IN (
                'NOT_ANALYZED', 'ANALYSIS_QUEUED', 'ANALYZING', 'ANALYZED', 'ANALYSIS_FAILED'
                )),
    ADD COLUMN analysis_model TEXT,
    ADD COLUMN analysis_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN analysis_completed_at pg_catalog.timestamptz,
    ADD COLUMN analysis_error_message TEXT;

CREATE INDEX idx_documents_analysis_status ON documents (analysis_status);