import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { WatchlistEntry } from "@/lib/api/companies";
import { renderWithQuery } from "@/test/render";
import { CompanySearch } from "./CompanySearch";

const search = vi.fn();
const list = vi.fn();
const add = vi.fn();
const remove = vi.fn();
vi.mock("@/lib/api/companies", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/companies")>()),
  companiesApi: { search: (...a: unknown[]) => search(...a) },
  watchlistApi: {
    list: () => list(),
    add: (...a: unknown[]) => add(...a),
    remove: (...a: unknown[]) => remove(...a),
  },
}));

const apple = { cik: "0000320193", name: "Apple Inc.", tickers: ["AAPL"], exchange: "Nasdaq" };
const microsoft = { cik: "0000789019", name: "Microsoft Corp", tickers: ["MSFT"], exchange: "Nasdaq" };
const watchedMicrosoft: WatchlistEntry = {
  id: 9,
  company: microsoft,
  formTypes: ["10-K", "10-Q", "8-K"],
  notificationsEnabled: true,
  createdAt: "2026-01-01T00:00:00Z",
};

describe("CompanySearch", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    list.mockResolvedValue([watchedMicrosoft]);
  });

  it("debounces input and shows results with watch status", async () => {
    search.mockResolvedValue([apple, microsoft]);
    renderWithQuery(<CompanySearch />);

    await userEvent.type(screen.getByRole("searchbox"), "ap");

    const results = await screen.findByRole("list", { name: "Search results" });
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("ap");
    expect(within(results).getByRole("link", { name: /Apple Inc\./ })).toHaveAttribute(
      "href",
      "/companies/0000320193",
    );
    expect(await within(results).findByRole("button", { name: "Stop watching Microsoft Corp" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(within(results).getByRole("button", { name: "Watch Apple Inc." })).toHaveAttribute("aria-pressed", "false");
  });

  it("watches a company and reflects it immediately", async () => {
    search.mockResolvedValue([apple]);
    const watchedApple = { ...watchedMicrosoft, id: 10, company: apple };
    add.mockResolvedValue(watchedApple);
    // After the add, the server's watchlist includes Apple.
    list.mockResolvedValueOnce([watchedMicrosoft]).mockResolvedValue([watchedMicrosoft, watchedApple]);
    renderWithQuery(<CompanySearch />);
    const user = userEvent.setup();

    await user.type(screen.getByRole("searchbox"), "apple");
    await user.click(await screen.findByRole("button", { name: "Watch Apple Inc." }));

    expect(add).toHaveBeenCalledWith("0000320193");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Stop watching Apple Inc." })).toHaveAttribute("aria-pressed", "true"),
    );
  });

  it("says when nothing matches", async () => {
    search.mockResolvedValue([]);
    renderWithQuery(<CompanySearch />);

    await userEvent.type(screen.getByRole("searchbox"), "zzzz");

    expect(await screen.findByText(/No companies match/)).toBeInTheDocument();
  });
});
