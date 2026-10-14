import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against the real application: the Next.js frontend, the
 * Spring Boot backend with PostgreSQL, Mailpit for email, and a stub of SEC EDGAR
 * (e2e/stub-sec.mjs). The backend, Postgres and Mailpit are started separately
 * (see docs/DEPLOYMENT.md, "End-to-end tests"); this config starts the SEC stub
 * and the production frontend build unless E2E_EXTERNAL_STUB or E2E_BASE_URL say
 * they are already running.
 */
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    ...(process.env.E2E_EXTERNAL_STUB
      ? []
      : [
          {
            command: "node e2e/stub-sec.mjs 4010",
            url: "http://localhost:4010/files/company_tickers_exchange.json",
            reuseExistingServer: !process.env.CI,
          },
        ]),
    ...(process.env.E2E_BASE_URL
      ? []
      : [
          {
            command: "npm run start -- --port 3000",
            url: "http://localhost:3000/login",
            reuseExistingServer: !process.env.CI,
            timeout: 120_000,
          },
        ]),
  ],
});
