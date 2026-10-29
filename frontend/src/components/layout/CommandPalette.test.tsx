import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { CommandPalette } from "./CommandPalette";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const search = vi.fn();
vi.mock("@/lib/api/companies", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/companies")>()),
  companiesApi: { search: (...a: unknown[]) => search(...a) },
}));

const apple = { cik: "0000320193", name: "Apple Inc.", tickers: ["AAPL"], exchange: "Nasdaq" };

describe("CommandPalette", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // jsdom does not lay out, so it has no scrolling.
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("opens with Ctrl+K and jumps to a page with the keyboard", async () => {
    renderWithQuery(<CommandPalette />);
    const user = userEvent.setup();

    await user.keyboard("{Control>}k{/Control}");
    const dialog = screen.getByRole("dialog", { name: "Search FilingLens" });
    await user.type(within(dialog).getByRole("combobox"), "watch");

    expect(within(dialog).getByRole("option", { name: /Watchlist/ })).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{Enter}");
    expect(push).toHaveBeenCalledWith("/watchlist");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("searches companies and opens the selected one", async () => {
    search.mockResolvedValue([apple]);
    renderWithQuery(<CommandPalette />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /Search/ }));
    await user.type(screen.getByRole("combobox"), "app");

    await user.click(await screen.findByRole("option", { name: /Apple Inc\./ }));
    expect(search).toHaveBeenCalledWith("app");
    expect(push).toHaveBeenCalledWith("/companies/0000320193");
  });

  it("closes on Escape", async () => {
    renderWithQuery(<CommandPalette />);
    const user = userEvent.setup();

    await user.keyboard("/");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
