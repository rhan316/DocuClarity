-- V1: Tabele fundamentu — dokumenty i outbox (Transactional Outbox pattern)
--
-- documents: rekordy przesłanych plików (metadane + status przetwarzania)
-- outbox: zdarzenia do publikacji na Redis Streams (spójność zapisu stanu i publikacji)
--
-- Uwaga: statusy jako TEXT + CHECK constraint zamiast enum PostgreSQL.
-- Spring Data JDBC mapuje String na varchar; natywne enumy PostgreSQL wymagałyby
-- custom converterów. CHECK constraint daje tę samą integralność danych.

CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_filename   TEXT NOT NULL,
    content_type    TEXT NOT NULL,
    content_length  BIGINT NOT NULL,
    -- Klucz obiektu w MinIO: documents/{id}/source
    storage_key     TEXT NOT NULL UNIQUE,
    status          TEXT NOT NULL DEFAULT 'UPLOADED'
                    CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW')),
    -- Liczba prób przetwarzania (retry)
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    -- Komunikat błędu (gdy status FAILED)
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indeks po statusie — worker pobiera dokumenty w danym statusie
CREATE INDEX idx_documents_status ON documents (status);
-- Indeks po dacie utworzenia — kolejność FIFO
CREATE INDEX idx_documents_created_at ON documents (created_at);

CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Id dokumentu, którego dotyczy zdarzenie
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    -- Typ zdarzenia (np. DOCUMENT_UPLOADED)
    event_type      TEXT NOT NULL,
    -- Payload zdarzenia jako TEXT (JSON) — Spring Data JDBC mapuje String na varchar,
    -- natywny JSONB wymagałby custom convertera
    payload         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    -- Liczba prób publikacji
    attempts        INTEGER NOT NULL DEFAULT 0,
    -- Komunikat błędu (gdy status FAILED)
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

-- Indeks po statusie — publisher pobiera wpisy PENDING
CREATE INDEX idx_outbox_status ON outbox (status);
-- Indeks po dokumencie — historii zdarzeń dla dokumentu
CREATE INDEX idx_outbox_document_id ON outbox (document_id);
-- Indeks po dacie utworzenia — kolejność FIFO
CREATE INDEX idx_outbox_created_at ON outbox (created_at);
