import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FilingSummary } from "@/lib/api/filings";
import { renderWithQuery } from "@/test/render";
import { FilingsList } from "./FilingsList";

const list = vi.fn();
vi.mock("@/lib/api/filings", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/filings")>()),
  filingsApi: { list: (...a: unknown[]) => list(...a) },
}));

function filing(accessionNumber: string, formType: string, extra: Partial<FilingSummary> = {}): FilingSummary {
  return {
    accessionNumber,
    formType,
    filingDate: "2025-10-31",
    reportDate: "2025-09-27",
    acceptanceAt: null,
    description: formType,
    items: [],
    primaryDocumentUrl: null,
    status: "DISCOVERED",
    ...extra,
  };
}

describe("FilingsList", () => {
  beforeEach(() => vi.clearAllMocks());

  it("lists filings with links, dates and 8-K item titles", async () => {
    list.mockResolvedValue({
      items: [
        filing("0000320193-25-000079", "10-K"),
        filing("0000320193-25-000071", "8-K", { items: ["2.02", "9.01"], reportDate: null }),
      ],
      nextCursor: null,
      stale: false,
    });
    renderWithQuery(<FilingsList cik="0000320193" />);

    const table = await screen.findByRole("table");
    expect(within(table).getByRole("link", { name: "10-K" })).toHaveAttribute(
      "href",
      "/filings/0000320193-25-000079",
    );
    expect(within(table).getAllByText("Oct 31, 2025")).toHaveLength(2);
    expect(within(table).getByText(/Item 2\.02 · Results of Operations and Financial Condition/)).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith("0000320193", [], null);
  });

  it("filters by form and loads more pages", async () => {
    list.mockImplementation(async (_cik: string, forms: string[], cursor: string | null) =>
      cursor
        ? { items: [filing("0000320193-24-000123", "10-K")], nextCursor: null, stale: false }
        : {
            items: [filing(forms.length ? "0000320193-25-000079" : "0000320193-25-000071", forms[0] ?? "8-K")],
            nextCursor: "next",
            stale: false,
          },
    );
    renderWithQuery(<FilingsList cik="0000320193" />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "10-K" }));
    expect(await screen.findByRole("link", { name: "10-K" })).toBeInTheDocument();
    expect(list).toHaveBeenLastCalledWith("0000320193", ["10-K"], null);

    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findAllByRole("link", { name: "10-K" })).toHaveLength(2);
    expect(list).toHaveBeenLastCalledWith("0000320193", ["10-K"], "next");
  });

  it("warns when the list may be out of date", async () => {
    list.mockResolvedValue({ items: [], nextCursor: null, stale: true });
    renderWithQuery(<FilingsList cik="0000320193" />);

    expect(await screen.findByText(/most recent filings may be missing/)).toBeInTheDocument();
  });
});
