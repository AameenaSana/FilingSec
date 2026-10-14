# FilingLens Architecture

Status: approved 2026-10-14. This document is the reference for the implementation phases.

## 1. Overview

```
 ┌──────────────────────────┐        ┌─────────────────────────────────────────────────────┐
 │ Next.js (App Router, TS) │  /api  │ Spring Boot modular monolith                        │
 │ Tailwind                 ├───────►│ identity │ company │ filing(ingestion) │ document    │
 │ rewrites → same origin   │  SSE   │ comparison │ research(AI/RAG) │ workspace         │
 └──────────────────────────┘◄───────┤ monitoring │ dashboard │ platform(shared infra)     │
                                     └──────┬───────────────┬──────────────┬──────────────┘
                                            │               │              │
                             PostgreSQL + pgvector      Blob store     SEC EDGAR (rate-limited),
                             (Flyway, JPA, job queue)   (FS / S3)      LLM API, embeddings, SMTP
```

## 2. Decisions

| Concern | Decision |
|---|---|
| Runtime | Java 25 (LTS), Spring Boot 4.1, Spring Modulith 2.1, Gradle 9 (Kotlin DSL); Next.js 16, React 19, Tailwind 4; PostgreSQL 17 + pgvector |
| Module boundaries | One top-level package per module, enforced by `ModularityTests` (`ApplicationModules.verify()`). Cross-module side effects use persistent Modulith events (`event_publication` table). |
| Vector search | pgvector in the same Postgres, combined with `tsvector` full-text search (hybrid retrieval) |
| Background work | Postgres `job` table claimed with `FOR UPDATE SKIP LOCKED` (`platform.jobs`) |
| Raw filings | `BlobStore` port. Local volume now, S3-compatible later. Immutable; keyed by CIK, accession and filename. |
| Auth | Server-side sessions (Spring Session JDBC), httpOnly SameSite=Lax cookie, CSRF cookie-to-header. The frontend proxies `/api`, so everything is same-origin. |
| LLM | `LlmClient` port; default adapter is Claude via the Anthropic API, with the model set in config |
| Embeddings | `EmbeddingClient` port; default Voyage AI |
| Email | In-app notifications plus SSE; email through SMTP, with Mailpit in development |
| Facts vs. AI | Deterministic text diffs (`text_change`) and AI interpretations (`change_interpretation`) are stored and displayed separately |

## 3. Modules

| Module | Owns | May depend on |
|---|---|---|
| `platform` (open) | ProblemDetail errors, request ids, logging, job queue, blob store, security baseline, config | — |
| `identity` | users, sessions, password/email tokens, current user | platform |
| `edgar` | `EdgarClient`: the only code that calls sec.gov (pacing, retries, throttle pause, gzip) | platform |
| `company` | CIK/ticker directory, company profile, search, watchlist | edgar, identity, platform |
| `filing` | filing metadata, raw fetch | edgar, company, platform |
| `document` | parse → sections → blocks → chunks; sanitized annotated source HTML | filing, platform |
| `comparison` | section/paragraph alignment, word diffs, AI interpretations | document, research API |
| `research` | embeddings, hybrid retrieval, Q&A, cross-company, citations, LLM usage | document, filing |
| `workspace` | research sessions, findings | identity, research API, comparison API |
| `monitoring` | polling watched companies, notifications, SSE, email | company, filing |
| `dashboard` | read-only aggregates | API packages of the others |

Events: `FilingDiscovered` → `FilingFetched` → `FilingParsed` → `FilingIndexed`, plus `ComparisonCompleted` and `NewFilingForWatchedCompany`.

## 4. SEC EDGAR access

Endpoints:
- `https://www.sec.gov/files/company_tickers.json`
- `https://data.sec.gov/submissions/CIK##########.json`
- `https://www.sec.gov/Archives/edgar/data/{cik}/{acc-no-dashes}/index.json`
- `https://www.sec.gov/Archives/edgar/data/{cik}/{acc-no-dashes}/{document}`

Compliance:
- Every request sends the declared `User-Agent` (`SEC_USER_AGENT`, validated at startup).
- One global token bucket limits traffic to ≤ 8 requests/second (SEC's limit is 10).
- gzip, conditional GETs, and exponential backoff on 429/403/5xx.
- A circuit breaker pauses traffic when throttled.
- Each document is fetched once and stored. Processing is lazy (on demand or for watched companies); there is no bulk crawling.

## 5. Data model (by module)

- **identity:**
  - `app_user`
  - `user_token` (hashed, single-use)
  - Spring Session tables
- **company:**
  - `company` (cik char(10) unique)
  - `company_ticker`
  - `watchlist_item` (unique user + company; form-type filter)
- **filing:**
  - `filing` (accession unique, form, filing/report dates, fiscal period, 8-K items, amendment link, processing status)
  - `filing_document` (with blob key and sha256)
- **document:**
  - `document_section` (form-specific `section_key`, e.g. `10K.ITEM_1A`, with confidence)
  - `text_block` (stable `block_ref` = `{sectionKey}/p{ordinal}`, content hash, char offsets, DOM anchor, parser version)
  - `text_chunk` (block ids, `vector` embedding, generated `tsvector`)
- **comparison:**
  - `filing_comparison`
  - `section_comparison`
  - `text_change` (ADDED / REMOVED / MODIFIED / MOVED, base and target block ids, word diff)
  - `change_interpretation` (AI output: model, prompt version)
- **research:**
  - `conversation` (FILING / MULTI_FILING / CROSS_COMPANY, scope)
  - `message`
  - `citation` (chunk, block, quote, verified)
  - `retrieval_trace`
  - `llm_usage`
- **workspace:**
  - `research_session`
  - `finding` (references a citation, change or block, plus a quoted snapshot)
- **monitoring:**
  - `company_poll_state`
  - `notification`
  - `notification_preference`
- **platform:**
  - `job`
  - `event_publication`

Every query on user-owned rows filters by `user_id` in the repository.

## 6. REST API (`/api/v1`, ProblemDetail errors)

| Area | Endpoints |
|---|---|
| System | `GET /system/info` |
| Auth | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`, `GET /auth/csrf`, `POST /auth/verify-email`, `POST /auth/password/forgot`, `POST /auth/password/reset` |
| Account | `PATCH /account`, `PUT /account/password`, `DELETE /account` |
| Companies | `GET /companies?q=`, `GET /companies/{cik}`, `GET /companies/{cik}/filings`, `POST /companies/{cik}/refresh` |
| Watchlist | `GET /watchlist`, `POST /watchlist`, `PATCH /watchlist/{id}`, `DELETE /watchlist/{id}` |
| Filings | `GET /filings/{acc}`, `GET /filings/{acc}/documents`, `GET /filings/{acc}/status`, `POST /filings/{acc}/process`, `GET /filings/{acc}/sections`, `GET /filings/{acc}/blocks`, `GET /filings/{acc}/blocks/{ref}`, `GET /filings/{acc}/source` |
| Comparison | `POST /comparisons`, `GET /companies/{cik}/comparison-candidates`, `GET /comparisons/{id}`, `GET /comparisons/{id}/sections`, `GET /comparisons/{id}/sections/{key}/changes`, `POST /comparisons/{id}/sections/{key}/interpretation`, `GET /comparisons/{id}/interpretations` |
| Research | `POST /conversations`, `GET /conversations`, `GET`/`PATCH`/`DELETE /conversations/{id}`, `POST /conversations/{id}/messages` (SSE), `GET /citations/{id}`, `POST /research/cross-company` |
| Workspace | CRUD on `/research-sessions`, `/research-sessions/{id}/findings`; `GET /findings` |
| Notifications | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all`, `GET /notifications/stream`, `GET`/`PUT /notification-preferences` |
| Dashboard | `GET /dashboard` |
| Admin | `GET /admin/jobs`, `POST /admin/jobs/{id}/retry` |

## 7. Workflows

1. **Directory sync:** daily `company_tickers.json` upsert.
2. **Discovery:** conditional GET of `submissions` upserts filings and emits `FilingDiscovered`.
3. **Fetch and process** (idempotent jobs keyed by accession number):
   - Fetch `index.json` and the primary document into the blob store.
   - Parse HTML/iXBRL (Jsoup; strip `ix:header`, unwrap `ix:*`) or plain text; PDF later.
   - Detect sections from a form-specific item catalog, disambiguating the table of contents.
   - Create stable blocks and a sanitized annotated source copy.
   - Chunk within sections (~600 tokens) and embed.
4. **Same-company comparison** (deterministic):
   - Align sections by key.
   - Match paragraphs: exact hash first, then similarity pairing, then word diffs; also detect MOVED.
   - Normalize boilerplate before comparing.
   - The optional AI interpretation is stored separately and must reference change ids.
5. **RAG Q&A:**
   - Hybrid retrieval (vector + full-text, RRF) within the conversation scope.
   - Delimited, labelled passages in the prompt; answers stream over SSE.
   - Citations are validated against the retrieved set and quotes are verified against block text.
   - The retrieval trace is persisted.
6. **Cross-company:** per-company balanced retrieval, then a structured comparison with per-company citations.
7. **Monitoring:**
   - Poll each distinct watched CIK once per interval.
   - Notify subscribers (in-app/SSE/email).
   - Auto-process and auto-compare new 10-K/10-Q filings.
8. **Dashboard:** read-only aggregation.

## 8. Security

- **Credentials:** Argon2id password hashing; login rate limiting and lockout.
- **Sessions and CSRF:** session id rotation on login; CSRF token on state-changing requests.
- **Authorization:** deny-by-default authorization; ownership enforced in repositories.
- **Input and output:**
  - Bean Validation on all requests.
  - Sanitized SEC HTML, rendered in a sandboxed iframe.
  - LLM output rendered as escaped markdown.
  - CSP, HSTS, `nosniff` and `frame-ancestors` headers.
- **Prompt injection:** filing text is treated as untrusted data; the model has no tools; citations are validated after generation.
- **Secrets:** only in environment variables; `.env` is gitignored; gitleaks runs in CI; logs never contain secrets.
- **Abuse and supply chain:** per-user LLM quotas; Dependabot.

### Document processing specifics (Phase 4)

- **Trigger:**
  - `FilingFetched` → a Modulith listener runs after commit and is persisted, so it is retried on restart.
  - The listener queues a `document.parse` job.
  - Parse state lives in `document_parse` with a `parser_version`. Viewing a document parsed by an older version re-parses it.
- **HTML / inline XBRL:**
  - Removes scripts, `display:none` content and `ix:header`, and unwraps `ix:*` tags.
  - Leaf block elements become blocks. Inline runs mixed with blocks are wrapped in a `span`.
  - A table with one non-empty row is a paragraph (typical "Item 7. | MD&A" headings); a table with one cell per row is layout and is descended into; any other table is a TABLE block ("cell | cell", one row per line).
  - Page numbers, running headers and "Table of Contents" back-links are dropped.
- **Plain text (.txt):** paragraphs split on blank lines, with SGML wrapper lines removed. PDFs are marked UNSUPPORTED.
- **Charset:** a declared charset is honored; otherwise strict UTF-8, falling back to Windows-1252.
- **Sections:**
  - Each form has an item catalog with stable keys: `10K.ITEM_1A`, `10Q.PART_II.ITEM_1A` and `8K.ITEM_2.02`.
  - Of the heading candidates for each item, the one followed by the most text wins, which skips table-of-contents entries.
  - Picks that break the standard item order are dropped (longest increasing subsequence).
  - Leading blocks form `COVER`; if no items are found, the whole document is one `DOCUMENT` section.
  - Each section carries a confidence: HIGH, MEDIUM or LOW.
- **Stable references:**
  - `block_ref = {section_key}/p{n}` (n is the position within the section), plus a document-wide `ordinal`, SHA-256 `content_hash`, and `char_start`/`char_end` offsets into the normalized text (blocks joined by a blank line).
  - The annotated viewer copy carries `data-fl-block="{ordinal}"`.
- **Viewer security:**
  - The sanitized copy follows an allow-list; relative links and images are made absolute to sec.gov, and CSS that loads URLs is removed.
  - It is served from `/api/v1/filings/{acc}/source` with `CSP: default-src 'none'; style-src 'unsafe-inline'; img-src https://www.sec.gov data:; frame-ancestors 'self'; sandbox allow-same-origin …` (no scripts) and `X-Frame-Options: SAMEORIGIN`.
  - It is shown in an `<iframe sandbox>` without `allow-scripts`. All other API responses keep `frame-ancestors 'none'` and `DENY`.
  - Next.js adds its page security headers only to non-`/api` paths.

### Filing specifics (Phase 3)

- **Discovery:**
  - A company's 10-K, 10-Q and 8-K filings (with `/A` amendments) are read from the submissions API when the stored list is older than 1 hour.
  - Older filings live on submissions history pages, imported once in a background job (`filing.history-import`).
  - Filing metadata is immutable, so inserts are idempotent (`ON CONFLICT DO NOTHING`).
  - If SEC is down, stored filings are served with `stale: true`.
- **Fiscal periods:** the period of report (`report_date`) is stored. Fiscal year and period labels are not derived, because SEC metadata alone does not give them reliably.
- **Fetching:**
  - Opening a filing queues a `filing.fetch` job.
  - The job reads the filing index page for the document list, falling back to the submissions API's primary document.
  - It stores the primary document's original bytes under `edgar/{cik}/{accession}/{filename}` with their SHA-256, then publishes `FilingFetched`.
  - Status: `DISCOVERED → FETCHING → FETCHED | FAILED`. Failed fetches can be retried.
  - Job handlers can implement `onPermanentFailure` to record a visible failure after the last retry.
- **Source references:** every filing and document keeps its canonical sec.gov URL. Archive file names from SEC are validated before they are used in URLs or storage keys.

### EDGAR access specifics (Phase 2)

EDGAR access is a separate `edgar` module. Both `company` and `filing` need it, and putting it in `filing` would have created a dependency cycle.
- **Pacing:** requests are spaced evenly at ≤ 8/s per instance, so no one-second window exceeds the cap, unlike a bursty token bucket.
- **Failures:**
  - 429 and 5xx are retried up to 4 attempts, honoring `Retry-After`.
  - A 403 pauses all SEC traffic for 10 minutes; SEC uses 403 for clients over its limits or not properly identified.
- **Metrics:** outcomes are counted in `filinglens.edgar.requests`.
- **Directory sync:** the ticker directory (`company_tickers_exchange.json`) syncs as a background job on first start and daily. Names from the directory never overwrite names from the submissions API, and a truncated download never deletes tickers.
- **Profiles:** company profiles load from the submissions API on first view and refresh after 24 hours. If SEC is unavailable, the stored profile is served.

### Identity specifics (Phase 1)

- **No account enumeration:**
  - Register, resend-verification and forgot-password always return the same 202 response.
  - An already-registered address receives a notice email instead of a new account.
  - Both paths hash the password so timing matches.
  - Login returns one generic error for unknown email, wrong password and locked account. Unknown and locked accounts still compare against a dummy hash.
- **Lockout and rate limits:**
  - Accounts lock for 15 minutes after 5 consecutive failures (configurable).
  - Per-IP token-bucket limits apply to login, email-sending and token endpoints.
  - Per-user limits apply to current-password checks.
- **Email links:**
  - Verification and reset tokens are 256-bit, single-use and stored only as SHA-256 hashes.
  - Links carry the token in the URL fragment (`#token=`), so it never reaches server logs or Referer headers. The page removes it from history.
  - Emails are sent asynchronously after commit. They deliberately do not go through Modulith persistent events, which would store raw tokens.
- **Sessions:**
  - Login rotates the session id and the CSRF token.
  - Password change revokes all other sessions; password reset and account deletion revoke all sessions. Revocation uses Spring Session's principal-name index.
  - Unverified accounts cannot sign in.

## 9. Testing

- **Unit:** JUnit 5, Mockito, AssertJ.
- **Parser golden tests:** committed EDGAR fixtures.
- **Integration:** Spring Modulith verification; Testcontainers (pgvector) for persistence and the job queue.
- **Web/security:** WebMvc slice tests for security and errors.
- **External services:** WireMock for SEC and the LLM.
- **RAG:** offline evaluation harness.
- **Frontend:** Vitest + React Testing Library.
- **E2E:** Playwright against Compose with stubbed externals.

## 10. Phases

| # | Phase | Status |
|---|---|---|
| 0 | Foundations: skeletons, platform module, migrations baseline, Compose, CI | Done |
| 1 | Identity and accounts | Done |
| 2 | Companies, EDGAR client, watchlist | Done |
| 3 | Filing discovery, metadata, raw fetch | Done |
| 4 | Document processing and source viewer | Done |
| 5 | Comparison engine and diff UI | |
| 6 | RAG Q&A with verified citations | |
| 7 | AI change interpretation and cross-company research | |
| 8 | Workspace (sessions, findings) | |
| 9 | Monitoring and notifications | |
| 10 | Dashboard | |
| 11 | Hardening: E2E, RAG evaluation, performance, security review, deployment docs | |
