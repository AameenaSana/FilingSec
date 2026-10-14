import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Comparison, TextChange } from "@/lib/api/comparisons";
import { renderWithQuery } from "@/test/render";
import { ComparisonView } from "./ComparisonView";

const get = vi.fn();
const changes = vi.fn();
vi.mock("@/lib/api/comparisons", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/comparisons")>()),
  comparisonsApi: { get: (...a: unknown[]) => get(...a), changes: (...a: unknown[]) => changes(...a) },
}));

const base = { accessionNumber: "0000320193-24-000123", formType: "10-K", filingDate: "2024-11-01", reportDate: "2024-09-28" };
const target = { accessionNumber: "0000320193-25-000079", formType: "10-K", filingDate: "2025-10-31", reportDate: "2025-09-27" };

function comparison(extra: Partial<Comparison> = {}): Comparison {
  return {
    id: 9,
    status: "READY",
    detail: null,
    company: { cik: "0000320193", name: "Apple Inc." },
    base,
    target,
    stats: { added: 1, removed: 1, modified: 1, moved: 0, unchanged: 40, numericOnly: 1 },
    computedAt: "2026-01-01T00:00:00Z",
    sections: [
      { key: "10K.ITEM_1", title: "Business", alignment: "MATCHED", added: 0, removed: 0, modified: 0, moved: 0, unchanged: 20, numericOnly: 0 },
      { key: "10K.ITEM_1A", title: "Risk Factors", alignment: "MATCHED", added: 1, removed: 1, modified: 1, moved: 0, unchanged: 20, numericOnly: 1 },
    ],
    ...extra,
  };
}

const riskChanges: TextChange[] = [
  {
    ordinal: 1,
    type: "MODIFIED",
    numericOnly: false,
    similarity: 0.9,
    base: { ordinal: 40, ref: "10K.ITEM_1A/p3", text: "investing in AI features" },
    target: { ordinal: 42, ref: "10K.ITEM_1A/p3", text: "investing heavily in generative AI features" },
    diff: [
      { op: "equal", text: "investing " },
      { op: "insert", text: "heavily " },
      { op: "equal", text: "in " },
      { op: "insert", text: "generative " },
      { op: "equal", text: "AI features" },
    ],
  },
  {
    ordinal: 2,
    type: "REMOVED",
    numericOnly: false,
    similarity: null,
    base: { ordinal: 41, ref: "10K.ITEM_1A/p4", text: "The COVID-19 pandemic affected operations." },
    target: null,
    diff: null,
  },
];

describe("ComparisonView", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows progress while documents are prepared", async () => {
    get.mockResolvedValue(comparison({ status: "WAITING", stats: null, sections: [] }));
    renderWithQuery(<ComparisonView id={9} />);

    expect(await screen.findByText(/Downloading and reading both filings/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /period ended Sep 28, 2024/ })).toHaveAttribute(
      "href",
      `/filings/${base.accessionNumber}`,
    );
  });

  it("opens the first changed section and renders word-level differences with source links", async () => {
    get.mockResolvedValue(comparison());
    changes.mockResolvedValue(riskChanges);
    renderWithQuery(<ComparisonView id={9} />);

    expect(await screen.findByText(/not an interpretation/)).toBeInTheDocument();
    const cards = await screen.findAllByRole("article");
    expect(changes).toHaveBeenCalledWith(9, "10K.ITEM_1A", true);

    const modified = cards[0];
    expect(within(modified).getByText("heavily", { selector: "ins", exact: false })).toBeInTheDocument();
    expect(within(modified).getByText("generative", { selector: "ins", exact: false })).toBeInTheDocument();
    expect(within(modified).getByRole("link", { name: /View in earlier filing/ })).toHaveAttribute(
      "href",
      `/filings/${base.accessionNumber}?block=40`,
    );
    expect(within(modified).getByRole("link", { name: /View in later filing/ })).toHaveAttribute(
      "href",
      `/filings/${target.accessionNumber}?block=42`,
    );
    const removed = cards[1];
    expect(within(removed).getByText(/COVID-19/, { selector: "del" })).toBeInTheDocument();
    expect(within(removed).queryByRole("link", { name: /later filing/ })).not.toBeInTheDocument();
  });

  it("can hide number-only changes and switch sections", async () => {
    get.mockResolvedValue(comparison());
    changes.mockResolvedValue([]);
    renderWithQuery(<ComparisonView id={9} />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole("checkbox", { name: /differ only in numbers \(1\)/ }));
    await waitFor(() => expect(changes).toHaveBeenLastCalledWith(9, "10K.ITEM_1A", false));

    await user.click(screen.getByRole("button", { name: /Item 1 · Business/ }));
    await waitFor(() => expect(changes).toHaveBeenLastCalledWith(9, "10K.ITEM_1", false));
    expect(await screen.findByText("No changes in this section.")).toBeInTheDocument();
  });

  it("reports a failed comparison", async () => {
    get.mockResolvedValue(comparison({ status: "FAILED", detail: "The earlier filing's document could not be downloaded or read" }));
    renderWithQuery(<ComparisonView id={9} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("could not be downloaded or read");
  });
});
