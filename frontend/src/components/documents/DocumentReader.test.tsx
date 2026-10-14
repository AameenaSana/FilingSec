import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DocumentStructure } from "@/lib/api/documents";
import { renderWithQuery } from "@/test/render";
import { DocumentReader } from "./DocumentReader";

let search = new URLSearchParams();
vi.mock("next/navigation", () => ({ useSearchParams: () => search }));

const structure = vi.fn();
const reparse = vi.fn();
vi.mock("@/lib/api/documents", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/documents")>()),
  documentsApi: { structure: (...a: unknown[]) => structure(...a), reparse: (...a: unknown[]) => reparse(...a) },
}));

const ACCESSION = "0000320193-25-000079";

const parsed: DocumentStructure = {
  status: "PARSED",
  detail: null,
  parserVersion: 1,
  parsedAt: "2026-01-01T00:00:00Z",
  blockCount: 3,
  sections: [
    { key: "COVER", title: "Cover page and front matter", headingText: null, ordinal: 1, firstBlock: 1, lastBlock: 1, confidence: "MEDIUM" },
    { key: "10K.ITEM_1A", title: "Risk Factors", headingText: "Item 1A. Risk Factors", ordinal: 2, firstBlock: 2, lastBlock: 2, confidence: "HIGH" },
    { key: "10Q.PART_II.ITEM_6", title: "Exhibits", headingText: "Item 6", ordinal: 3, firstBlock: 3, lastBlock: 3, confidence: "LOW" },
  ],
};

/** Puts annotated blocks into the (otherwise empty) jsdom frame document. */
function fillFrame() {
  const frame = screen.getByTitle("Filing document") as HTMLIFrameElement;
  const doc = frame.contentDocument!;
  doc.open();
  doc.write(
    '<html><body><p data-fl-block="1">Cover</p><p data-fl-block="2">Risk</p><p data-fl-block="3">Exhibits</p></body></html>',
  );
  doc.close();
  // The frame has its own window and DOM classes.
  const scrollIntoView = vi.fn();
  (frame.contentWindow as unknown as { Element: typeof Element }).Element.prototype.scrollIntoView = scrollIntoView;
  return { frame, doc, scrollIntoView };
}

describe("DocumentReader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    search = new URLSearchParams();
  });

  it("renders the sandboxed viewer and labels sections by item", async () => {
    structure.mockResolvedValue(parsed);
    renderWithQuery(<DocumentReader accession={ACCESSION} />);

    const frame = (await screen.findByTitle("Filing document")) as HTMLIFrameElement;
    expect(frame.getAttribute("src")).toBe(`/api/v1/filings/${ACCESSION}/source`);
    expect(frame.getAttribute("sandbox")).toBe("allow-same-origin allow-popups allow-popups-to-escape-sandbox");
    expect(screen.getByRole("button", { name: "Item 1A · Risk Factors" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Part II, Item 6 · Exhibits/ })).toHaveTextContent("(approx.)");
  });

  it("scrolls to and highlights a section's first block", async () => {
    structure.mockResolvedValue(parsed);
    renderWithQuery(<DocumentReader accession={ACCESSION} />);
    await screen.findByTitle("Filing document");
    const { doc, scrollIntoView } = fillFrame();

    await userEvent.click(screen.getByRole("button", { name: "Item 1A · Risk Factors" }));

    const risk = doc.querySelector('[data-fl-block="2"]')!;
    expect(risk.hasAttribute("data-fl-highlight")).toBe(true);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: /Cover page/ }));
    expect(risk.hasAttribute("data-fl-highlight")).toBe(false);
    expect(doc.querySelector('[data-fl-block="1"]')!.hasAttribute("data-fl-highlight")).toBe(true);
  });

  it("highlights the block named in ?block= once the frame loads", async () => {
    search = new URLSearchParams("block=3");
    structure.mockResolvedValue(parsed);
    renderWithQuery(<DocumentReader accession={ACCESSION} />);
    await screen.findByTitle("Filing document");
    const { frame, doc } = fillFrame();

    fireEvent.load(frame);

    expect(doc.querySelector('[data-fl-block="3"]')!.hasAttribute("data-fl-highlight")).toBe(true);
  });

  it("shows progress while parsing and a retry after failure", async () => {
    structure.mockResolvedValueOnce({ ...parsed, status: "PARSING", sections: [] });
    const { unmount } = renderWithQuery(<DocumentReader accession={ACCESSION} />);
    expect(await screen.findByText("Preparing the document…")).toBeInTheDocument();
    unmount();

    structure.mockResolvedValue({ ...parsed, status: "FAILED", detail: "No text was found", sections: [] });
    reparse.mockResolvedValue({ ...parsed, status: "PARSING", sections: [] });
    renderWithQuery(<DocumentReader accession={ACCESSION} />);
    expect(await screen.findByText("No text was found")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(reparse).toHaveBeenCalledWith(ACCESSION);
  });
});
