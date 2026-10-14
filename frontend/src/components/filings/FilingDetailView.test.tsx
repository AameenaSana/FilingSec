import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FilingDetail } from "@/lib/api/filings";
import { renderWithQuery } from "@/test/render";
import { FilingDetailView } from "./FilingDetailView";

const get = vi.fn();
const requestFetch = vi.fn();
vi.mock("@/lib/api/filings", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/filings")>()),
  filingsApi: {
    get: (...a: unknown[]) => get(...a),
    requestFetch: (...a: unknown[]) => requestFetch(...a),
  },
}));

const ACCESSION = "0000320193-25-000079";

function detail(extra: Partial<FilingDetail> = {}): FilingDetail {
  return {
    accessionNumber: ACCESSION,
    company: { cik: "0000320193", name: "Apple Inc." },
    formType: "10-K",
    filingDate: "2025-10-31",
    reportDate: "2025-09-27",
    acceptanceAt: "2025-10-31T06:01:26Z",
    description: "10-K",
    items: [],
    sizeBytes: 9374416,
    xbrl: true,
    inlineXbrl: true,
    fileNumber: "001-36743",
    status: "FETCHED",
    statusDetail: null,
    fetchedAt: "2026-01-01T00:00:00Z",
    filingIndexUrl: "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/0000320193-25-000079-index.htm",
    primaryDocumentUrl: "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm",
    documents: [
      {
        sequence: 1,
        filename: "aapl-20250927.htm",
        type: "10-K",
        description: "10-K",
        sizeBytes: 1485736,
        primary: true,
        sourceUrl: "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm",
        stored: true,
        sha256: "ab".repeat(32),
      },
    ],
    ...extra,
  };
}

describe("FilingDetailView", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows metadata and documents linking to the SEC originals", async () => {
    get.mockResolvedValue(detail());
    renderWithQuery(<FilingDetailView accession={ACCESSION} />);

    expect(await screen.findByRole("heading", { name: /Form 10-K/ })).toHaveTextContent("Sep 27, 2025");
    expect(screen.getByRole("link", { name: "aapl-20250927.htm" })).toHaveAttribute(
      "href",
      "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm",
    );
    expect(screen.getByRole("link", { name: /Filing index on SEC/ })).toHaveAttribute("target", "_blank");
    expect(screen.getByText("1.4 MB")).toBeInTheDocument();
    expect(screen.getByText("Stored")).toBeInTheDocument();
    expect(requestFetch).not.toHaveBeenCalled();
  });

  it("requests the download once for a new filing and polls until stored", async () => {
    get.mockResolvedValueOnce(detail({ status: "DISCOVERED", documents: [] })).mockResolvedValue(detail());
    requestFetch.mockResolvedValue(detail({ status: "FETCHING", documents: [] }));
    renderWithQuery(<FilingDetailView accession={ACCESSION} />);

    expect(await screen.findByText("Downloading from SEC…")).toBeInTheDocument();
    expect(requestFetch).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByText("Stored")).toBeInTheDocument(), { timeout: 5000 });
    expect(requestFetch).toHaveBeenCalledTimes(1);
  });

  it("offers a retry when the download failed", async () => {
    get.mockResolvedValue(detail({ status: "FAILED", statusDetail: "SEC EDGAR is unavailable", documents: [] }));
    requestFetch.mockResolvedValue(detail({ status: "FETCHING", documents: [] }));
    renderWithQuery(<FilingDetailView accession={ACCESSION} />);

    expect(await screen.findByText("SEC EDGAR is unavailable")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(requestFetch).toHaveBeenCalledTimes(1);
  });
});
