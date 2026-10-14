-- Monitoring of watched companies and user notifications.

-- When each watched company's filing list was last polled from SEC.
CREATE TABLE company_poll_state
(
    company_id      BIGINT PRIMARY KEY REFERENCES company (id) ON DELETE CASCADE,
    last_polled_at  TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_error      VARCHAR(500)
);

-- Comparisons started automatically for a newly discovered filing; watchers are
-- notified when they complete.
CREATE TABLE monitored_comparison
(
    comparison_id BIGINT PRIMARY KEY REFERENCES filing_comparison (id) ON DELETE CASCADE,
    filing_id     BIGINT      NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE notification
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type          VARCHAR(30)  NOT NULL CHECK (type IN ('NEW_FILING', 'COMPARISON_READY')),
    company_id    BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    filing_id     BIGINT       NOT NULL REFERENCES filing (id) ON DELETE CASCADE,
    comparison_id BIGINT REFERENCES filing_comparison (id) ON DELETE SET NULL,
    title         VARCHAR(300) NOT NULL,
    body          VARCHAR(1000) NOT NULL,
    -- Path within the app, e.g. /filings/{accession}.
    link          VARCHAR(300) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    read_at       TIMESTAMPTZ,
    -- One notification of each kind per user and filing, however often it is discovered.
    CONSTRAINT notification_user_type_filing_uk UNIQUE (user_id, type, filing_id)
);
CREATE INDEX notification_user_idx ON notification (user_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification (user_id) WHERE read_at IS NULL;

CREATE TABLE notification_preference
(
    user_id       UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    email_enabled BOOLEAN     NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
