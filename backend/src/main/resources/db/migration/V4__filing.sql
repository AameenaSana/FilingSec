-- Filings discovered from the EDGAR submissions API and their documents.

CREATE TABLE filing
(
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id              BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    accession_number        CHAR(20)     NOT NULL UNIQUE CHECK (accession_number ~ '^[0-9]{10}-[0-9]{2}-[0-9]{6}$'),
    form_type               VARCHAR(20)  NOT NULL,
    filing_date             DATE         NOT NULL,
    -- Period of report; null for forms without one.
    report_date             DATE,
    acceptance_at           TIMESTAMPTZ,
    primary_document        VARCHAR(255),
    primary_doc_description VARCHAR(500),
    -- 8-K item numbers, comma-separated (e.g. "2.02,9.01").
    items                   VARCHAR(200),
    size_bytes              BIGINT,
    is_xbrl                 BOOLEAN      NOT NULL DEFAULT FALSE,
    is_inline_xbrl          BOOLEAN      NOT NULL DEFAULT FALSE,
    file_number             VARCHAR(50),
    film_number             VARCHAR(50),
    status                  VARCHAR(20)  NOT NULL DEFAULT 'DISCOVERED'
        CHECK (status IN ('DISCOVERED', 'FETCHING', 'FETCHED', 'FAILED')),
    status_detail           VARCHAR(1000),
    fetched_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL
);
CREATE INDEX filing_company_date_idx ON filing (company_id, filing_date DESC, id DESC);
CREATE INDEX filing_company_form_period_idx ON filing (company_id, form_type, report_date DESC);

CREATE TABLE filing_document
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filing_id   BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    sequence    INT          NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    doc_type    VARCHAR(50),
    description VARCHAR(500),
    size_bytes  BIGINT,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    source_url  VARCHAR(500) NOT NULL,
    -- Set once the original bytes are stored in the blob store.
    blob_key    VARCHAR(500),
    sha256      CHAR(64),
    content_type VARCHAR(100),
    fetched_at  TIMESTAMPTZ,
    CONSTRAINT filing_document_filename_uk UNIQUE (filing_id, filename)
);

-- Per-company progress of filing discovery.
CREATE TABLE company_filing_sync
(
    company_id               BIGINT PRIMARY KEY REFERENCES company (id) ON DELETE CASCADE,
    recent_synced_at         TIMESTAMPTZ,
    -- Submissions history pages (older filings) already imported.
    imported_history_files   TEXT[] NOT NULL DEFAULT '{}'
);
