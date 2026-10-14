# Security review

Review of FilingLens at the end of Phase 11 (October 2026): the controls in place, what changed as a result of the review, and the known limitations. Report vulnerabilities privately to the repository owner rather than in a public issue.

## Threat model

- **Assets:** user accounts and sessions; users' private research (conversations, findings, notes, watchlists, notifications); API keys for Anthropic and Voyage; the application's standing with SEC (a misbehaving client can be blocked by SEC).
- **Untrusted input:** everything from browsers; SEC documents (arbitrary HTML from third-party filers); LLM output; content in the database written by other users.
- **Out of scope:** the hosting platform, the reverse proxy and the database server's own hardening.

## Controls

### Authentication and sessions
- Passwords hashed with Argon2id. `PasswordPolicy` follows NIST SP 800-63B: length limits, no composition rules, and rejection of passwords containing the email address or made of too few distinct characters.
- Email verification before login; single-use, expiring tokens stored only as SHA-256 hashes; tokens travel in URL fragments so they never reach server logs or `Referer` headers.
- Login is rate limited per IP; accounts lock after 5 failures for 15 minutes; responses do not reveal whether an email is registered.
- Server-side sessions (Spring Session JDBC) with the id rotated at login; `HttpOnly`, `SameSite=Lax`, `Secure` in production; 8-hour timeout. Password changes and account deletion require the current password.

### Authorization
- Deny by default: every endpoint except registration, login, verification, password reset, CSRF and health requires a session.
- Every query on user-owned rows (conversations, citations, sessions, findings, watchlists, notifications, preferences, dashboard) filters by the user's id in SQL; other users' ids return 404, not 403.
- Data derived from public SEC filings (filings, documents, comparisons, interpretations) is shared between users by design. Knowing a comparison id reveals only that some user compared two public filings.

### Request handling
- CSRF protection with a double-submit token on all state-changing requests.
- Bean Validation on every request body and parameter; identifiers (CIK, accession numbers, section keys, block refs) are checked against strict patterns before use.
- **New:** API request bodies are capped at 256 KB (`RequestSizeLimitFilter`).
- All SQL is parameterized; `LIKE` searches escape wildcards.
- Errors are RFC 9457 problem details with stable codes; stack traces and exception messages are never returned.

### Rate limits and resource caps
- Per-user or per-IP token buckets on login, email sending, token redemption, password confirmation, company views, filing lists, document fetches, comparisons, interpretations and questions.
- **New:** limits on creating conversations (10/min) and cross-company research (5/min), which trigger indexing and paid embedding calls, and on workspace writes (60/min).
- Per-user caps: 200 watched companies, 5,000 findings, **200 research sessions (new)**, 5 live notification connections, and a daily LLM token quota.

### Untrusted SEC documents
- Filing HTML is sanitized (Jsoup allow-list) and served from a dedicated endpoint with its own CSP: no scripts (`sandbox` without `allow-scripts`, no `script-src`), images only from `www.sec.gov`, no forms, framable only by the app's own origin. The frontend shows it in a sandboxed iframe.
- Only `www.sec.gov` and `data.sec.gov` are contacted (configured base URLs, never URLs taken from documents). File names from filing indexes are validated before use, so `../` paths in an index cannot escape the filing's directory.

### LLM integration
- Prompts treat filing text as data, not instructions; answers must cite passages, and every citation is checked verbatim against the stored text. Unverified citations are flagged in the UI.
- Saved findings never take quote text from the client: the server copies it from the referenced citation, change or passage.
- Model output is rendered as text (React escaping), never as HTML.
- API keys come only from the environment and are never logged or returned.

### Browser
- Backend API responses: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, HSTS.
- **New:** frontend pages send a Content-Security-Policy (`default-src 'self'`, no plugins, no framing, `form-action 'self'`, connections only to the same origin) and HSTS in production.

### Operations
- Secrets are never committed: `.env` is gitignored and CI runs a gitleaks secret scan on every change.
- Containers run as non-root users; the actuator (metrics, health) is on a separate internal port in production.
- Dependabot keeps dependencies current.

## Findings of this review

| # | Finding | Severity | Resolution |
|---|---|---|---|
| 1 | Conversation creation and cross-company research were not rate limited; each can trigger SEC downloads and paid embedding calls | Medium | Fixed: per-user limits |
| 2 | Research sessions per user were unbounded | Low | Fixed: cap of 200 |
| 3 | No overall limit on API request body size; validation ran only after the whole body was parsed | Low | Fixed: 256 KB limit |
| 4 | Frontend pages had no Content-Security-Policy | Low | Fixed |
| 5 | `npm audit` reports 5 high-severity advisories (`braces` ReDoS via `eslint-config-next`) | Low | Accepted: development-only lint tooling that is not shipped; the suggested fix downgrades Next.js. Production dependencies have no known vulnerabilities |
| 6 | Frontend CSP allows `'unsafe-inline'` scripts, which Next.js needs for hydration without per-request nonces | Low | Accepted; no user or SEC content is rendered as HTML in the app (SEC HTML is confined to the sandboxed viewer) |
| 7 | Rate limits are per instance | Info | Documented in DEPLOYMENT.md |

## Verification

- Security behaviour is covered by tests: `HttpSecurityTest`, `CsrfCookieTest`, the `*ApiTest` slice tests (authentication, validation, ownership), the integration tests (isolation between users), `RequestSizeLimitFilterTest`, and the Playwright `security.spec.ts` (redirects, 401 problem details, CSRF, CSP without violations).
- The production frontend build was checked in a browser for CSP violations.
