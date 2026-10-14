# FilingLens

SEC filing intelligence platform: search public companies, track a watchlist, compare
10-K / 10-Q / 8-K filings across periods, and ask questions answered from the filing
text with verifiable citations.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the architecture and implementation plan.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot 4 modular monolith (Java 25, Gradle) |
| `frontend/` | Next.js 16 app (React 19, TypeScript, Tailwind CSS 4) |
| `docker-compose.yml` | Postgres (pgvector) and the full containerized stack |
| `.github/` | CI (build, tests, secret scan, image builds) and Dependabot |
| `docs/` | Architecture and decisions |

## Prerequisites

- JDK 25 (e.g. Eclipse Temurin). Gradle is provided by the wrapper.
- Node.js 22+
- Docker (Docker Desktop on Windows/macOS), used for Postgres and Testcontainers tests

## Configuration

All secrets and environment-specific values come from environment variables. Copy
`.env.example` to `.env` and fill it in. `.env` is gitignored; never commit real values.

`SEC_USER_AGENT` is required: SEC asks every client to declare who it is with a contact
email (for example `FilingLens you@example.com`). The backend refuses to start without it.

## Running locally

```bash
# 1. Database
cp .env.example .env            # then edit values
docker compose up -d postgres mailpit   # Mailpit catches emails: http://localhost:8025

# 2. Backend (http://localhost:8080)
cd backend
SPRING_PROFILES_ACTIVE=dev SEC_USER_AGENT="FilingLens you@example.com" ./gradlew bootRun

# 3. Frontend (http://localhost:3000; proxies /api to the backend)
cd frontend
npm install
npm run dev
```

Alternatively, run everything in containers: `docker compose --profile app up --build`.

## Tests

```bash
cd backend && ./gradlew test     # Testcontainers tests are skipped when Docker is unavailable
cd frontend && npm test && npm run lint && npm run typecheck
```

## Status

Phases 0 (foundations), 1 (identity and accounts), 2 (companies, EDGAR client, watchlist) and
3 (filing discovery, metadata and document fetching) , 4 (document processing and source
viewer) and 5 (filing comparison) are complete. See the phase plan in the architecture document.
