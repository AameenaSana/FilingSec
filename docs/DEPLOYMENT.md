# Deploying FilingLens

FilingLens runs as two containers, the Next.js frontend and the Spring Boot backend, plus PostgreSQL 17 with pgvector and an SMTP server. The browser only talks to the frontend; the frontend proxies `/api/*` to the backend, so session and CSRF cookies stay first-party.

```
browser ──HTTPS──► reverse proxy ──► frontend :3000 ──/api/*──► backend :8080 ──► PostgreSQL
                                                                     │  :8081 actuator (internal only)
                                                                     ├──► SEC EDGAR (www.sec.gov, data.sec.gov)
                                                                     ├──► Anthropic API, Voyage AI (optional)
                                                                     └──► SMTP
```

## 1. Requirements

| Component | Version / size |
|---|---|
| Docker + Compose | Compose v2 |
| PostgreSQL | 17 with the `vector` and `pg_trgm` extensions (`pgvector/pgvector:pg17`) |
| Backend | Java 25 (in the image); 1 GB memory is enough to start, 2 GB recommended |
| Frontend | Node 22 (in the image); 256–512 MB |
| Disk | Original SEC documents are kept in the blob store (`STORAGE_ROOT`); a 10-K is typically 1–15 MB |

## 2. Configuration

Copy `.env.example` to `.env` and fill it in. **Never commit `.env` or put secrets in images**; in production supply them from your platform's secret store as environment variables.

| Variable | Required | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | yes | Database password (Compose also uses `POSTGRES_DB`, `POSTGRES_USER`) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | outside Compose | JDBC connection when not using the bundled database |
| `DB_POOL_SIZE` | no (20) | Maximum database connections per backend instance |
| `SEC_USER_AGENT` | yes | Declared User-Agent with a contact email, required by SEC's [access policy](https://www.sec.gov/os/accessing-edgar-data) |
| `APP_PUBLIC_URL` | yes | Browser-facing origin, used for links in emails |
| `MAIL_FROM`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | yes in production | Outgoing email (verification, password reset, notifications) |
| `LLM_API_KEY` | no | Anthropic API key; without it question answering and AI interpretation are disabled |
| `LLM_MODEL` | no (`claude-opus-5-5`) | Claude model |
| `EMBEDDING_API_KEY` | no | Voyage AI key; without it retrieval is keyword-only |
| `MONITORING_ENABLED`, `MONITORING_POLL_INTERVAL` | no (`true`, `PT15M`) | Polling of watched companies |
| `STORAGE_ROOT` | no (`/data/blobs` in Compose) | Where original SEC documents are stored; must be persistent |

The backend refuses to start if required settings are missing or invalid (for example a `SEC_USER_AGENT` without an email address).

## 3. Running with Compose

```bash
cp .env.example .env        # then edit it
docker compose --profile app up -d --build
```

This starts PostgreSQL, Mailpit, the backend and the frontend on `http://localhost:3000`. Ports are bound to `127.0.0.1` only. In production replace Mailpit with a real SMTP server (`SMTP_HOST`, credentials) and put a TLS-terminating reverse proxy in front of the frontend.

Database migrations (Flyway) run automatically when the backend starts. The backend's readiness probe (`/actuator/health/readiness` on port 8081) reports `UP` once migrations are applied and the database is reachable; the frontend container waits for it.

## 4. Reverse proxy and TLS

Terminate TLS in front of the frontend and forward to port 3000. Requirements:

- **HTTPS only.** The `prod` profile marks the session cookie `Secure`; both apps send HSTS.
- **Forwarded headers.** Pass `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`; the backend uses them (`forward-headers-strategy: framework`) for client IPs in rate limiting and for secure-cookie decisions. Make sure clients cannot spoof them past the proxy.
- **Streaming.** Answers and notifications use server-sent events. Disable response buffering for `/api/` (for nginx: `proxy_buffering off;`, and a `proxy_read_timeout` of at least 35 minutes for `/api/v1/notifications/stream`). The backend also sends `X-Accel-Buffering: no`.
- **Body size.** API request bodies over 256 KB are rejected by the backend; the proxy can enforce the same limit.
- **Never expose port 8081** (actuator, including Prometheus metrics) or the database.

Example nginx location block:

```nginx
location / {
    proxy_pass http://frontend:3000;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_buffering off;
    proxy_read_timeout 40m;
    client_max_body_size 256k;
}
```

## 5. Scaling and availability

- **Background jobs** use a PostgreSQL queue claimed with `FOR UPDATE SKIP LOCKED`, and monitoring claims each company's poll with an atomic update, so several backend instances can run without doing work twice.
- **Rate limits** (per user or IP) are kept in memory per instance. With N instances the effective limit is up to N times higher; keep sticky sessions or accept the looser limit.
- **Live notifications** reach clients connected to the instance that handled the event. Clients on other instances catch up within two minutes (the header refetches). Sticky sessions keep most users on one instance.
- **SEC rate limit.** Each instance paces itself at the configured rate (default 8 requests/s). SEC's limit is 10 requests/s per client, so with more than one instance lower `filinglens.sec.max-requests-per-second` (for example 4 with two instances).
- **Sessions** are stored in PostgreSQL (Spring Session JDBC), so any instance can serve any request.
- **Graceful shutdown** waits up to 30 seconds for in-flight requests; interrupted jobs are reclaimed after the lock timeout (15 minutes).

## 6. Backups and data

Back up the PostgreSQL database (for example nightly `pg_dump` plus WAL archiving for point-in-time recovery) and the blob store directory together. Everything derived from SEC documents (parsed sections, comparisons, search index) can be rebuilt from the stored originals, which can themselves be downloaded from SEC again; user data (accounts, watchlists, conversations, findings, notifications) cannot.

Deleting an account removes the user's watchlist, conversations, research sessions, findings, notifications and preferences (foreign keys cascade). Shared data derived from public SEC filings is kept.

## 7. Upgrades

1. Back up the database.
2. Deploy the new images. Migrations run on startup and are forward-only; never edit an applied migration.
3. Watch the readiness probe and logs. To roll back, restore the backup taken in step 1 and deploy the previous images.

## 8. Observability

- **Logs** are structured JSON (Elastic Common Schema) in the `prod` profile. Every line for a request carries its `requestId`, which is returned to clients in `X-Request-Id` and in error responses.
- **Metrics:** Prometheus format at `:8081/actuator/prometheus`.
- **Health:** `:8081/actuator/health/liveness` and `/readiness`.
- Useful signals: job failures (`FAILED` jobs in the `job` table), `company_poll_state.last_error`, SEC throttling warnings (`SEC refused` in logs), and LLM usage per user in `llm_usage`.

## 9. End-to-end tests

The Playwright suite in `frontend/e2e` runs the real application against a stub of SEC EDGAR (`frontend/e2e/stub-sec.mjs`), which serves one company with two annual reports. CI runs it in the `e2e` job. To run it locally:

```bash
docker compose up -d postgres mailpit
node frontend/e2e/stub-sec.mjs 4010 &

# Backend, pointed at the stub
cd backend
DB_URL=jdbc:postgresql://localhost:5432/filinglens DB_USERNAME=filinglens DB_PASSWORD=<password> \
SEC_USER_AGENT="FilingLens E2E you@example.com" \
FILINGLENS_SEC_WWWBASEURL=http://localhost:4010 FILINGLENS_SEC_DATABASEURL=http://localhost:4010 \
SPRING_MAIL_HOST=localhost SPRING_MAIL_PORT=1025 MONITORING_ENABLED=false \
./gradlew bootRun

# Frontend (production build) and tests
cd frontend
npm run build
npx playwright install chromium
E2E_EXTERNAL_STUB=1 npm run e2e
```

Use a fresh database: the stub only knows Apple (CIK 320193), and the company directory is loaded from it on first startup.

## 10. RAG evaluation

`backend/src/test/resources/rag-eval/` holds an evaluation set: a fictional annual report and questions, each answered by one known passage.

- **Retrieval** (`RagRetrievalEvaluationTest`) runs with the integration tests (Docker required) and fails if recall@5 or mean reciprocal rank drop below their thresholds. With `EMBEDDING_API_KEY` set it measures hybrid retrieval; otherwise keyword-only.
- **Answers** (`RagAnswerEvaluationTest`) calls Claude for every question, so it costs money and runs only when asked:

  ```bash
  RAG_EVAL_LIVE=true LLM_API_KEY=... ./gradlew test --tests '*RagAnswerEvaluationTest'
  ```

  It checks that citations are verified verbatim and that each answer cites the passage that answers it, and reports refusals and token use.

Reports are written to `backend/build/reports/rag-eval/`.
