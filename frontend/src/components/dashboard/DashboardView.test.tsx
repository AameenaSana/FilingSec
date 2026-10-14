import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Dashboard } from "@/lib/api/dashboard";
import { renderWithQuery } from "@/test/render";
import { DashboardView } from "./DashboardView";

vi.mock("@/lib/auth/AuthProvider", () => ({ useAuth: () => ({ state: { user: { displayName: "Ada" } } }) }));

const get = vi.fn();
vi.mock("@/lib/api/dashboard", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/dashboard")>()),
  dashboardApi: { get: () => get() },
}));

const EMPTY: Dashboard = {
  summary: { watchedCompanies: 0, filingsLast30Days: 0, unreadNotifications: 0, findings: 0, lastCheckedAt: null },
  companies: [],
  recentFilings: [],
  recentChanges: [],
  notifications: [],
  conversations: [],
  sessions: [],
};

const FULL: Dashboard = {
  summary: {
    watchedCompanies: 1,
    filingsLast30Days: 2,
    unreadNotifications: 1,
    findings: 4,
    lastCheckedAt: new Date().toISOString(),
  },
  companies: [
    {
      cik: "0000320193",
      name: "Apple Inc.",
      ticker: "AAPL",
      formTypes: ["10-K", "8-K"],
      latestFiling: {
        accessionNumber: "0000320193-25-000080",
        formType: "8-K",
        filingDate: "2025-11-05",
        reportDate: null,
      },
      unreadNotifications: 1,
      lastCheckedAt: null,
    },
  ],
  recentFilings: [
    {
      cik: "0000320193",
      companyName: "Apple Inc.",
      ticker: "AAPL",
      accessionNumber: "0000320193-25-000080",
      formType: "8-K",
      filingDate: "2025-11-05",
      reportDate: null,
      items: "2.02",
    },
  ],
  recentChanges: [
    {
      comparisonId: 7,
      companyName: "Apple Inc.",
      ticker: "AAPL",
      formType: "10-K",
      basePeriod: "2024-09-28",
      targetPeriod: "2025-09-27",
      added: 5,
      removed: 0,
      modified: 9,
      moved: 0,
      computedAt: "2025-11-01T00:00:00Z",
    },
  ],
  notifications: [
    {
      id: 1,
      type: "NEW_FILING",
      title: "Apple Inc. filed a 8-K",
      link: "/filings/0000320193-25-000080",
      createdAt: new Date().toISOString(),
      read: false,
    },
  ],
  conversations: [{ id: 3, title: "Apple AI risks", updatedAt: "2025-11-01T00:00:00Z" }],
  sessions: [{ id: 4, title: "AI disclosures", findingCount: 1, updatedAt: "2025-11-01T00:00:00Z" }],
};

describe("DashboardView", () => {
  beforeEach(() => vi.clearAllMocks());

  it("guides new users to build a watchlist", async () => {
    get.mockResolvedValue(EMPTY);
    renderWithQuery(<DashboardView />);

    expect(screen.getByRole("heading", { name: "Welcome, Ada" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Search for companies" })).toHaveAttribute("href", "/companies");
  });

  it("shows changes, filings, companies, notifications and research", async () => {
    get.mockResolvedValue(FULL);
    renderWithQuery(<DashboardView />);

    const changes = await screen.findByRole("region", { name: "What changed" });
    const change = within(changes).getByRole("link", { name: /AAPL 10-K/ });
    expect(change).toHaveAttribute("href", "/compare/7");
    expect(change).toHaveTextContent("5 added");
    expect(change).toHaveTextContent("9 modified");
    expect(change).not.toHaveTextContent("removed");

    const filings = screen.getByRole("region", { name: "Recent filings" });
    expect(within(filings).getByRole("link", { name: "8-K" })).toHaveAttribute("href", "/filings/0000320193-25-000080");
    expect(filings).toHaveTextContent("Results of Operations");

    expect(screen.getByRole("link", { name: /Unread notifications/ })).toHaveTextContent("1");
    expect(screen.getByText(/SEC last checked for new filings just now/)).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Watched companies" })).getByText("1 new")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Apple AI risks" })).toHaveAttribute("href", "/research/3");
    expect(screen.getByRole("link", { name: "AI disclosures" })).toHaveAttribute("href", "/workspace/sessions/4");
    expect(screen.getByText("1 finding")).toBeInTheDocument();
  });
});
