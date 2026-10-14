-- Retrieval index over parsed filings and grounded Q&A conversations.

-- Retrieval units: consecutive blocks of one section. Citations resolve to the
-- individual blocks (block_refs / block_ordinals) inside a chunk.
CREATE TABLE text_chunk
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filing_id       BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    ordinal         INT          NOT NULL,
    section_key     VARCHAR(40)  NOT NULL,
    section_title   VARCHAR(300) NOT NULL,
    block_ordinals  INT[]        NOT NULL,
    block_refs      TEXT[]       NOT NULL,
    -- Block texts in order; the chunk text is these joined by blank lines.
    block_texts     TEXT[]       NOT NULL,
    text            TEXT         NOT NULL,
    embedding       vector(1024),
    embedding_model VARCHAR(50),
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    CONSTRAINT text_chunk_ordinal_uk UNIQUE (filing_id, ordinal)
);
CREATE INDEX text_chunk_embedding_idx ON text_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX text_chunk_tsv_idx ON text_chunk USING gin (tsv);
CREATE INDEX text_chunk_filing_idx ON text_chunk (filing_id);

CREATE TABLE filing_index
(
    filing_id       BIGINT PRIMARY KEY REFERENCES filing (id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('INDEXING', 'INDEXED', 'FAILED')),
    parser_version  INT,
    chunk_count     INT,
    -- False when no embedding provider is configured: retrieval is full-text only.
    embedded        BOOLEAN     NOT NULL DEFAULT FALSE,
    embedding_model VARCHAR(50),
    detail          VARCHAR(1000),
    indexed_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE conversation
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX conversation_user_idx ON conversation (user_id, updated_at DESC);

-- The filings a conversation is grounded in.
CREATE TABLE conversation_filing
(
    conversation_id BIGINT NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    filing_id       BIGINT NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    position        INT    NOT NULL,
    PRIMARY KEY (conversation_id, filing_id)
);

CREATE TABLE message
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id   BIGINT      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role              VARCHAR(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content           TEXT        NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('STREAMING', 'COMPLETE', 'REFUSED', 'FAILED')),
    model             VARCHAR(60),
    input_tokens      INT,
    output_tokens     INT,
    cache_read_tokens INT,
    error             VARCHAR(1000),
    created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX message_conversation_idx ON message (conversation_id, id);

-- A claim in an answer and the exact source blocks supporting it. Source fields
-- are snapshots so citations stay resolvable if the index is rebuilt.
CREATE TABLE citation
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id          BIGINT       NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    ordinal             INT          NOT NULL,
    filing_id           BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    section_key         VARCHAR(40)  NOT NULL,
    section_title       VARCHAR(300) NOT NULL,
    block_refs          TEXT[]       NOT NULL,
    first_block_ordinal INT          NOT NULL,
    cited_text          TEXT         NOT NULL,
    -- Character range of the answer text this citation supports.
    answer_start        INT          NOT NULL,
    answer_end          INT          NOT NULL,
    -- True when the cited text was found verbatim in the stored source blocks.
    verified            BOOLEAN      NOT NULL
);
CREATE INDEX citation_message_idx ON citation (message_id, ordinal);

-- Exactly which passages the model was given for an answer (audit trail).
CREATE TABLE retrieval_trace
(
    message_id   BIGINT NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    rank         INT    NOT NULL,
    chunk_id     BIGINT NOT NULL,
    filing_id    BIGINT NOT NULL,
    score        REAL   NOT NULL,
    vector_rank  INT,
    text_rank    INT,
    PRIMARY KEY (message_id, rank)
);

CREATE TABLE llm_usage
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    purpose       VARCHAR(40) NOT NULL,
    model         VARCHAR(60) NOT NULL,
    input_tokens  INT         NOT NULL,
    output_tokens INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX llm_usage_user_day_idx ON llm_usage (user_id, created_at);
