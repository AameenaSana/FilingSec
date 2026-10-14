-- FilingLens baseline schema: extensions and shared platform tables.
-- Extensions must be creatable by the migration user (true for the bundled
-- pgvector image; on managed Postgres, pre-provision them).

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- Durable background job queue (see platform.jobs.JobQueue).
CREATE TABLE job
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type         VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    priority     INT          NOT NULL DEFAULT 0,
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    run_after    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by    VARCHAR(200),
    locked_at    TIMESTAMPTZ,
    last_error   TEXT,
    dedupe_key   VARCHAR(300),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX job_claimable_idx ON job (priority DESC, run_after, id) WHERE status = 'PENDING';
CREATE INDEX job_running_idx ON job (locked_at) WHERE status = 'RUNNING';
-- At most one active job per dedupe key; finished jobs do not block re-enqueueing.
CREATE UNIQUE INDEX job_active_dedupe_key_idx ON job (dedupe_key) WHERE status IN ('PENDING', 'RUNNING');

-- Spring Modulith event publication registry (v2 structure, from spring-modulith-events-jdbc 2.1.1).
CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
