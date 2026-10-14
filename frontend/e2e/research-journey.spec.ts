import { expect, test } from "@playwright/test";
import { signUp } from "./support";

const LATEST_10K = "0000320193-25-000079";

/**
 * The main research journey against stubbed SEC data: find a company, watch it,
 * read its annual report, save a passage, compare it with the previous year and
 * see everything on the dashboard and in the workspace.
 */
test("a new user researches a company from search to saved findings", async ({ page }) => {
  await signUp(page);
  await expect(page.getByRole("link", { name: "Search for companies" })).toBeVisible();

  // Find and watch the company. The directory is loaded from the SEC stub at startup.
  await page.goto("/companies");
  await page.getByLabel("Search companies").fill("Apple");
  const results = page.getByRole("list", { name: "Search results" });
  await expect(results.getByText("Apple Inc.")).toBeVisible();
  await results.getByRole("button", { name: "Watch Apple Inc." }).click();
  await expect(results.getByRole("button", { name: "Stop watching Apple Inc." })).toBeVisible();

  // The company page discovers its filings from the stub; open the latest annual report,
  // whose document is then downloaded and processed.
  await page.goto("/companies/0000320193");
  await page.locator(`a[href="/filings/${LATEST_10K}"]`).first().click();
  await expect(page).toHaveURL(new RegExp(`/filings/${LATEST_10K}`));
  await expect(page.getByRole("heading", { name: "Document" })).toBeVisible({ timeout: 60_000 });
  const sections = page.getByRole("navigation", { name: "Sections" });
  await expect(sections.getByRole("button", { name: /Risk Factors/ })).toBeVisible({ timeout: 60_000 });

  // Select a paragraph inside the sandboxed viewer and save it as a finding.
  const viewer = page.frameLocator('iframe[title="Filing document"]');
  await viewer.getByText("Artificial intelligence features introduce new risks").click();
  await expect(page.getByText(/Highlighted passage #\d+/)).toBeVisible();
  await page.getByRole("button", { name: "Save passage as finding" }).click();
  await page.getByLabel("Note").fill("New AI risk factor this year");
  await page.getByLabel(/Tags/).fill("ai, risk");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved ·")).toBeVisible();

  // Compare with the previous annual report (suggested automatically).
  await expect(page.getByLabel("Compare with")).toHaveValue("0000320193-24-000123", { timeout: 30_000 });
  await page.getByRole("button", { name: "Compare", exact: true }).click();
  await expect(page).toHaveURL(/\/compare\/\d+/);
  // Computing waits for both documents to be downloaded and processed.
  await page.getByRole("button", { name: /Risk Factors/ }).click({ timeout: 90_000 });
  const changes = page.getByRole("region", { name: "Changes" });
  await expect(changes.getByText(/Artificial intelligence features introduce new risks/)).toBeVisible();
  await expect(changes.getByText("Added").first()).toBeVisible();

  // The workspace shows the saved passage verbatim with its note and tags.
  await page.goto("/workspace");
  const findings = page.getByRole("region", { name: "Findings" });
  await expect(findings.getByText(/Artificial intelligence features introduce new risks/)).toBeVisible();
  await expect(findings.getByText("New AI risk factor this year")).toBeVisible();
  await expect(findings.getByRole("list", { name: "Tags" })).toContainText("risk");
  await expect(findings.getByRole("link", { name: /View in filing/ })).toHaveAttribute(
    "href",
    new RegExp(`/filings/${LATEST_10K}\\?block=\\d+`),
  );

  // The dashboard summarizes the watched company, its filings and the comparison.
  await page.goto("/dashboard");
  await expect(page.getByRole("region", { name: "Watched companies" })).toContainText("Apple Inc.");
  await expect(page.getByRole("region", { name: "What changed" }).getByRole("link", { name: /AAPL 10-K/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /Saved findings/ })).toContainText("1");
});
