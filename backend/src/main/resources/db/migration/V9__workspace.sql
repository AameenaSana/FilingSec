-- Research sessions and saved findings.

CREATE TABLE research_session
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX research_session_user_idx ON research_session (user_id, updated_at DESC);

-- A saved piece of evidence. The quote and source fields are snapshots taken by
-- the server from the referenced citation, change or passage, never supplied by
-- the client, so a finding always shows genuine filing text.
CREATE TABLE finding
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Null when the finding is not filed in a session.
    research_session_id BIGINT REFERENCES research_session (id) ON DELETE SET NULL,
    source_type         VARCHAR(20)  NOT NULL CHECK (source_type IN ('CITATION', 'CHANGE', 'PASSAGE')),
    filing_id           BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    accession_number    CHAR(20)     NOT NULL,
    company_name        VARCHAR(300) NOT NULL,
    form_type           VARCHAR(20)  NOT NULL,
    report_date         DATE,
    section_key         VARCHAR(40)  NOT NULL,
    section_title       VARCHAR(300) NOT NULL,
    block_refs          TEXT[]       NOT NULL,
    block_ordinal       INT          NOT NULL,
    quoted_text         TEXT         NOT NULL,
    -- For a modified change: the earlier filing's text and its location.
    previous_text       TEXT,
    previous_accession  CHAR(20),
    previous_block_ordinal INT,
    change_type         VARCHAR(10),
    comparison_id       BIGINT REFERENCES filing_comparison (id) ON DELETE SET NULL,
    change_ordinal      INT,
    conversation_id     BIGINT REFERENCES conversation (id) ON DELETE SET NULL,
    note                TEXT,
    tags                TEXT[]       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX finding_user_session_idx ON finding (user_id, research_session_id, created_at DESC);
CREATE INDEX finding_tags_idx ON finding USING gin (tags);
