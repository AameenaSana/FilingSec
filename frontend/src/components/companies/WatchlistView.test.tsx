import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { WatchlistEntry } from "@/lib/api/companies";
import { renderWithQuery } from "@/test/render";
import { WatchlistView } from "./WatchlistView";

const list = vi.fn();
const update = vi.fn();
const remove = vi.fn();
vi.mock("@/lib/api/companies", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/companies")>()),
  watchlistApi: {
    list: () => list(),
    update: (...a: unknown[]) => update(...a),
    remove: (...a: unknown[]) => remove(...a),
  },
}));

const entry: WatchlistEntry = {
  id: 3,
  company: { cik: "0000320193", name: "Apple Inc.", tickers: ["AAPL"], exchange: "Nasdaq" },
  formTypes: ["10-K", "8-K"],
  notificationsEnabled: true,
  createdAt: "2026-01-01T00:00:00Z",
};

describe("WatchlistView", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows an empty state with a link to search", async () => {
    list.mockResolvedValue([]);
    renderWithQuery(<WatchlistView />);

    expect(await screen.findByRole("link", { name: "Search for a company" })).toHaveAttribute("href", "/companies");
  });

  it("toggles tracked forms and notifications", async () => {
    list.mockResolvedValue([entry]);
    update.mockImplementation(async (id, formTypes, notificationsEnabled) => ({
      ...entry,
      formTypes,
      notificationsEnabled,
    }));
    renderWithQuery(<WatchlistView />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole("checkbox", { name: "10-Q" }));
    expect(update).toHaveBeenLastCalledWith(3, ["10-K", "8-K", "10-Q"], true);

    await user.click(screen.getByRole("checkbox", { name: "Notify me" }));
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
    expect(update.mock.calls[1][2]).toBe(false);
  });

  it("keeps the last selected form from being unchecked", async () => {
    list.mockResolvedValue([{ ...entry, formTypes: ["10-K"] }]);
    renderWithQuery(<WatchlistView />);

    expect(await screen.findByRole("checkbox", { name: "10-K" })).toBeDisabled();
  });

  it("removes a company", async () => {
    list.mockResolvedValueOnce([entry]).mockResolvedValue([]);
    remove.mockResolvedValue(undefined);
    renderWithQuery(<WatchlistView />);

    await userEvent.click(await screen.findByRole("button", { name: "Remove Apple Inc. from watchlist" }));

    expect(remove).toHaveBeenCalledWith(3);
    expect(await screen.findByText(/not watching any companies/)).toBeInTheDocument();
  });
});
