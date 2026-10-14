-- Parsed filing documents: sections and stable, citable text blocks.

-- Parse state per filing. Re-parsing with a new parser version replaces the
-- filing's sections and blocks.
CREATE TABLE document_parse
(
    filing_id          BIGINT PRIMARY KEY REFERENCES filing (id) ON DELETE CASCADE,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PARSING', 'PARSED', 'UNSUPPORTED', 'FAILED')),
    parser_version     INT          NOT NULL,
    detail             VARCHAR(1000),
    -- Sanitized copy of the original with data-fl-block anchors, for the viewer.
    annotated_blob_key VARCHAR(500),
    section_count      INT,
    block_count        INT,
    text_length        INT,
    parsed_at          TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE document_section
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filing_id           BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    -- Form-specific stable key, e.g. 10K.ITEM_1A, 10Q.PART_II.ITEM_1A, 8K.ITEM_2.02, COVER.
    section_key         VARCHAR(40)  NOT NULL,
    title               VARCHAR(300) NOT NULL,
    -- The heading as written in the filing; null for synthetic sections.
    heading_text        VARCHAR(500),
    ordinal             INT          NOT NULL,
    first_block_ordinal INT          NOT NULL,
    last_block_ordinal  INT          NOT NULL,
    confidence          VARCHAR(10)  NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT document_section_key_uk UNIQUE (filing_id, section_key)
);

CREATE TABLE text_block
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filing_id    BIGINT      NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    section_id   BIGINT      NOT NULL REFERENCES document_section (id) ON DELETE CASCADE,
    -- Position in the whole document; matches data-fl-block in the annotated HTML.
    ordinal      INT         NOT NULL,
    -- Stable citation reference: {section_key}/p{position within section}.
    block_ref    VARCHAR(80) NOT NULL,
    block_type   VARCHAR(20) NOT NULL CHECK (block_type IN ('HEADING', 'PARAGRAPH', 'LIST_ITEM', 'TABLE')),
    text         TEXT        NOT NULL,
    content_hash CHAR(64)    NOT NULL,
    -- Offsets into the normalized document text (blocks joined by blank lines).
    char_start   INT         NOT NULL,
    char_end     INT         NOT NULL,
    CONSTRAINT text_block_ordinal_uk UNIQUE (filing_id, ordinal),
    CONSTRAINT text_block_ref_uk UNIQUE (filing_id, block_ref)
);
CREATE INDEX text_block_section_idx ON text_block (section_id, ordinal);
