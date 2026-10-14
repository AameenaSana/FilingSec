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
| `ai` | `AiModel` (Claude via the Anthropic Java SDK, citations, refusal fallbacks) and `AiUsage` (per-user token quota) | platform |
| `comparison` | section/paragraph alignment, word diffs, AI interpretations | document, filing, ai |
| `research` | embeddings, hybrid retrieval, Q&A, cross-company, citations, LLM usage | document, filing |
| `workspace` | research sessions, findings, Markdown export | research (`CitationLookup`), comparison (`ChangeLookup`), document (`DocumentContent.block`), filing |
| `monitoring` | polling watched companies, notifications, SSE, email, auto-compare | company (`Watchlists`), filing (`FilingsDiscovered`, `refreshRecent`), comparison (`ComparisonRequests`, `ComparisonCompleted`), identity (`UserDirectory`) |
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
  - `monitored_comparison`
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
| Workspace | `GET`/`POST /research-sessions`, `GET`/`PATCH`/`DELETE /research-sessions/{id}`, `GET /research-sessions/{id}/export` (Markdown), `POST /findings`, `GET /findings?sessionId=&unfiled=&q=&tag=`, `PATCH`/`DELETE /findings/{id}` |
| Notifications | `GET /notifications?unreadOnly=&limit=` (with `unreadCount`), `POST /notifications/{id}/read`, `POST /notifications/read-all`, `GET /notifications/stream` (SSE), `GET`/`PUT /notification-preferences` |
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

### Monitoring specifics (Phase 9)

- **Polling:**
  - Every minute a scheduler claims each watched company not polled within `filinglens.monitoring.poll-interval` (default 15 minutes) with an atomic upsert on `company_poll_state`, and queues a `monitoring.poll` job. A company is polled once per interval however many users watch it, even with several instances.
  - The job calls `FilingCatalog.refreshRecent`, which goes through the shared EDGAR client and so keeps to SEC's rate limit and back-off. Failures are retried by the job queue; the last error is kept on the poll state.
- **Discovery:** the filing module publishes `FilingsDiscovered` whenever a refresh (by the poller or by a user browsing) stores filings it did not know, except on a company's first sync, whose filings are history rather than news. At most the 10 most recent new filings are acted on at once.
- **Notifications:**
  - Each watcher who follows the filing's form and has notifications on for the company gets a `NEW_FILING` notification. A unique key (user, type, filing) makes repeats harmless.
  - New filings are downloaded and processed automatically, so they are ready to read and question. A new original 10-K or 10-Q is compared with its suggested predecessor (previous annual report, or the same quarter a year earlier); when that comparison completes, watchers get a `COMPARISON_READY` notification linking to it.
  - Delivery: stored in the app; pushed live over `GET /notifications/stream` (SSE with `unread` and `notification` events and a 25-second keep-alive, at most 5 connections per user); and emailed through a retried `monitoring.email` job unless the user turned email off in settings or has not verified their address. Pushes are sent after the transaction commits.
  - Live pushes reach connections on the instance that handled the event. With several instances, other clients catch up on the next list refresh (the header refetches every 2 minutes).
- **Configuration:** `MONITORING_ENABLED`, `MONITORING_POLL_INTERVAL`; tests disable the scheduler.

### Workspace specifics (Phase 8)

- **Findings are references, resolved by the server:** the client sends only a source reference: a citation id, a comparison change (comparison id, section key, change ordinal) or a filing passage (accession, block ordinal). The server reads the quote and source details through `CitationLookup`, `ChangeLookup` and `DocumentContent.block` and stores a snapshot. Saved evidence is therefore always genuine filing text with a working link to its location, and a client cannot save invented quotes.
- **Change findings:** a MODIFIED change keeps both the earlier and the later text with links to each. A REMOVED change quotes the earlier filing.
- **Ownership:** every query is scoped by `user_id`. Citations resolve only within the user's own conversations, and findings can be filed only in the user's own sessions.
- **Sessions:** deleting a session keeps its findings as unfiled (`ON DELETE SET NULL`). Notes are free text; tags are normalized (lower case, hyphenated, at most 10 per finding). Each user can keep at most 5,000 findings.
- **Export:** `GET /research-sessions/{id}/export` returns Markdown, oldest finding first, with each quote, note, tags, a link to the SEC filing index and a link to the passage in FilingLens.
- **UI:** "Save finding" on cited sources, change cards and a clicked passage in the filing reader; `/workspace` lists sessions and searchable, tag-filterable findings; `/workspace/sessions/{id}` edits, exports and deletes a session.

### AI interpretation and cross-company specifics (Phase 7)

- **`ai` module:** owns the LLM (`AiModel`: grounded generation with `search_result` passages and citations), the configuration (`filinglens.ai.*`) and usage accounting (`AiUsage`). Both `research` and `comparison` depend on it, so the LLM is not reached through another feature module.
- **Change interpretations:**
  - Generated per comparison section on request, as the background job `comparison.interpret`.
  - Each non-numeric change is sent as one passage: earlier and later text for MODIFIED, a single side otherwise. At most 60 changes or about 60K characters are sent, and the UI says when changes were left out.
  - The response starts with `Significance: HIGH|MEDIUM|LOW|NONE`, which is parsed off. Citations map to change ordinals and are verified against the change texts.
  - Stored in `change_interpretation`, separate from `text_change`, and shared per section and prompt version. The requester's quota is charged.
  - The UI shows it in a distinct, labelled "AI interpretation" panel that links to the change cards it cites. The factual change list stays the primary record.
- **Cross-company comparison:**
  - `POST /api/v1/conversations/cross-company` takes 2–4 CIKs, a form and an optional fiscal year.
  - It resolves each company's latest original filing of the form via `FilingCatalog.latestFiling`, refreshing the filing list from SEC if stale. The fiscal year is the calendar year in which the period of report ends.
  - It creates a conversation over those filings.
  - Conversations spanning several companies retrieve passages evenly per company (interleaved), and the system prompt asks for a per-company answer with each company cited to its own filing, followed by similarities and differences.

### Research / RAG specifics (Phase 6)

- **Indexing:**
  - `DocumentParsed` → `research.index` job.
  - Blocks are grouped per section into chunks of about 350 words (at most 600); headings start new chunks.
  - Each chunk stores its block ordinals, refs and texts.
  - Embeddings use Voyage `voyage-4` at 1024 dimensions, with `input_type` document or query, and are stored in `vector(1024)` with an HNSW index plus a generated English `tsvector`. Without `EMBEDDING_API_KEY`, chunks are indexed for full-text search only.
- **Retrieval:**
  - Hybrid: cosine vector search and an OR-of-terms full-text search, merged by reciprocal rank fusion (k = 60), top 12.
  - Vector search computes exact distance over the conversation's filings, deliberately bypassing the approximate index, whose global candidates could all belong to other filings.
  - Follow-up questions are retrieved together with the previous question.
- **Answering:**
  - Claude (`claude-opus-5-5`, effort `medium`) through the official Anthropic Java SDK, streamed. The model is configurable with `LLM_MODEL`.
  - Each retrieved chunk is sent as a `search_result` content block whose content is the chunk's individual blocks, with citations enabled. Claude's `search_result_location` citations therefore identify the passage and an exact block range, which maps to our `block_ref`s and viewer ordinals.
  - Server-side refusal fallbacks are enabled (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). Refusals are stored as REFUSED without citations.
  - The system prompt restricts answers to the provided passages, requires citations, and treats passage text as data, never instructions.
- **Verification:** every citation's `cited_text` is checked verbatim (ignoring whitespace) against the stored text of the cited blocks. Unverified citations are kept but flagged in the UI.
- **Audit:**
  - Each answer stores its retrieval trace (chunk, fused score, vector and text ranks) and token usage.
  - Per-user daily token quota (2M by default) and 20 questions per minute.
  - Without `LLM_API_KEY`, Q&A returns `503 ai-not-configured`.
- **Streaming:**
  - `POST /api/v1/conversations/{id}/messages` returns `text/event-stream` with events `start`, `delta`, then `done` or `error`. Validation, not-ready and quota errors are returned as ProblemDetail before streaming starts.
  - The response is marked `no-transform` so proxies and compression do not buffer it. Async dispatches are permitted in the security chain because the original request was already authorized.

### Comparison specifics (Phase 5)

- **Lifecycle:** `WAITING → COMPUTING → READY | FAILED`.
  - Creating a comparison makes sure both filings are downloaded and parsed, through `DocumentContent.prepare` and `FilingCatalog.requestDocuments`.
  - A `DocumentParsed` listener advances waiting comparisons.
  - If a document is unreadable, the comparison becomes FAILED; requesting it again retries.
  - The earlier filing (by period of report) is always the base.
  - Results are shared per (base, target, algorithm version).
- **Algorithm (version 1, deterministic):**
  - Sections are aligned by key. Blocks are diffed in order by content hash (Myers, via java-diff-utils).
  - Leftover blocks are paired greedily by word-multiset Dice similarity (≥ 0.5, or ≥ 0.7 for blocks under 6 words). Pairs become MODIFIED with a word-level diff; the rest are ADDED or REMOVED.
  - Exact-text ADDED/REMOVED pairs (≥ 40 characters) anywhere in the document become MOVED.
  - Changes whose texts differ only in numbers are flagged `numeric_only`.
  - Changes are ordered by position in the later filing.
- **Storage:** each change stores both block texts (snapshots), refs and ordinals, so it remains verifiable and links to `/filings/{acc}?block={ordinal}` in either filing.
- **Facts only:** comparison results contain no AI output. AI interpretations (Phase 7) will be stored separately and labelled.
- **Suggested comparison:** for a 10-K, the previous annual report (originals preferred over amendments); for a 10-Q, the same quarter a year earlier, else the previous quarter; for an 8-K, the previous 8-K.

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
| 5 | Comparison engine and diff UI | Done |
| 6 | RAG Q&A with verified citations | Done |
| 7 | AI change interpretation and cross-company research | Done |
| 8 | Workspace (sessions, findings) | Done |
| 9 | Monitoring and notifications | Done |
| 10 | Dashboard | |
| 11 | Hardening: E2E, RAG evaluation, performance, security review, deployment docs | |
