-- AI-generated interpretations of a comparison section's changes. Kept separate
-- from text_change, which holds only the factual differences.

CREATE TABLE change_interpretation
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    section_comparison_id BIGINT      NOT NULL REFERENCES section_comparison (id) ON DELETE CASCADE,
    prompt_version        INT         NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'READY', 'REFUSED', 'FAILED')),
    -- The model's own assessment of how material the changes appear.
    significance          VARCHAR(10) CHECK (significance IN ('HIGH', 'MEDIUM', 'LOW', 'NONE')),
    summary               TEXT,
    -- [{"marker":1,"changeOrdinal":3,"citedText":"...","answerStart":0,"answerEnd":42,"verified":true}]
    citations             JSONB,
    changes_considered    INT,
    changes_total         INT,
    model                 VARCHAR(60),
    input_tokens          INT,
    output_tokens         INT,
    requested_by          UUID REFERENCES app_user (id) ON DELETE SET NULL,
    detail                VARCHAR(1000),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT change_interpretation_section_uk UNIQUE (section_comparison_id, prompt_version)
);
