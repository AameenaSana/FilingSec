-- Companies (from SEC's ticker directory and submissions API) and user watchlists.

CREATE TABLE company
(
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cik                    CHAR(10)     NOT NULL UNIQUE CHECK (cik ~ '^[0-9]{10}$'),
    name                   VARCHAR(300) NOT NULL,
    entity_type            VARCHAR(50),
    sic                    VARCHAR(10),
    sic_description        VARCHAR(200),
    category               VARCHAR(100),
    fiscal_year_end        CHAR(4),
    state_of_incorporation VARCHAR(10),
    -- When company details were last refreshed from the submissions API.
    profile_synced_at      TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX company_name_trgm_idx ON company USING gin (name gin_trgm_ops);

CREATE TABLE company_ticker
(
    ticker     VARCHAR(20) PRIMARY KEY,
    company_id BIGINT      NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    exchange   VARCHAR(50),
    -- Position in SEC's directory; the lowest rank per company is its primary listing.
    rank       INT         NOT NULL,
    synced_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX company_ticker_company_idx ON company_ticker (company_id, rank);

CREATE TABLE watchlist_item
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    company_id BIGINT      NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    form_types TEXT[]      NOT NULL DEFAULT ARRAY ['10-K', '10-Q', '8-K'],
    notify     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT watchlist_item_user_company_uk UNIQUE (user_id, company_id),
    CONSTRAINT watchlist_item_form_types_ck CHECK (form_types <@ ARRAY ['10-K', '10-Q', '8-K'] AND cardinality(form_types) > 0)
);
CREATE INDEX watchlist_item_company_idx ON watchlist_item (company_id);
