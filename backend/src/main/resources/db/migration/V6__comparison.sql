-- Period-over-period comparisons of filings. Results are deterministic textual
-- differences only; AI interpretations are stored separately (later phase).

CREATE TABLE filing_comparison
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Base is the earlier filing, target the later one.
    base_filing_id    BIGINT      NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    target_filing_id  BIGINT      NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    algorithm_version INT         NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('WAITING', 'COMPUTING', 'READY', 'FAILED')),
    detail            VARCHAR(1000),
    -- {"added":..,"removed":..,"modified":..,"moved":..,"unchanged":..,"numericOnly":..}
    stats             JSONB,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    computed_at       TIMESTAMPTZ,
    CONSTRAINT filing_comparison_pair_uk UNIQUE (base_filing_id, target_filing_id, algorithm_version),
    CONSTRAINT filing_comparison_distinct_ck CHECK (base_filing_id <> target_filing_id)
);
CREATE INDEX filing_comparison_target_idx ON filing_comparison (target_filing_id);

CREATE TABLE section_comparison
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    comparison_id      BIGINT       NOT NULL REFERENCES filing_comparison (id) ON DELETE CASCADE,
    section_key        VARCHAR(40)  NOT NULL,
    title              VARCHAR(300) NOT NULL,
    ordinal            INT          NOT NULL,
    -- MATCHED: in both filings; ADDED: only in the later one; REMOVED: only in the earlier one.
    alignment          VARCHAR(10)  NOT NULL CHECK (alignment IN ('MATCHED', 'ADDED', 'REMOVED')),
    added_count        INT          NOT NULL,
    removed_count      INT          NOT NULL,
    modified_count     INT          NOT NULL,
    moved_count        INT          NOT NULL,
    unchanged_count    INT          NOT NULL,
    numeric_only_count INT          NOT NULL,
    CONSTRAINT section_comparison_key_uk UNIQUE (comparison_id, section_key)
);

-- One detected difference. Texts are snapshots so a result stays readable and
-- verifiable even if documents are re-parsed later.
CREATE TABLE text_change
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    section_comparison_id BIGINT      NOT NULL REFERENCES section_comparison (id) ON DELETE CASCADE,
    ordinal               INT         NOT NULL,
    change_type           VARCHAR(10) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'MODIFIED', 'MOVED')),
    -- True when the texts differ only in numbers (e.g. years, amounts).
    numeric_only          BOOLEAN     NOT NULL DEFAULT FALSE,
    similarity            REAL,
    base_block_ordinal    INT,
    base_block_ref        VARCHAR(80),
    base_text             TEXT,
    target_block_ordinal  INT,
    target_block_ref      VARCHAR(80),
    target_text           TEXT,
    -- [{"op":"equal|insert|delete","text":"..."}] for MODIFIED changes.
    word_diff             JSONB
);
CREATE INDEX text_change_section_idx ON text_change (section_comparison_id, ordinal);
