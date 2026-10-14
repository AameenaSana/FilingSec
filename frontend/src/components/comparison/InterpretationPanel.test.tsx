import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Interpretation } from "@/lib/api/comparisons";
import { renderWithQuery } from "@/test/render";
import { InterpretationPanel } from "./InterpretationPanel";

const interpretation = vi.fn();
const requestInterpretation = vi.fn();
vi.mock("@/lib/api/comparisons", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/comparisons")>()),
  comparisonsApi: {
    interpretation: (...a: unknown[]) => interpretation(...a),
    requestInterpretation: (...a: unknown[]) => requestInterpretation(...a),
  },
}));

const ready: Interpretation = {
  status: "READY",
  significance: "HIGH",
  summary: "Reliance on generative AI increased materially.",
  citations: [
    { marker: 1, changeOrdinal: 3, citedText: "rely heavily on generative AI", answerStart: 0, answerEnd: 33, verified: true },
    { marker: 2, changeOrdinal: 5, citedText: "invented", answerStart: 0, answerEnd: 48, verified: false },
  ],
  model: "claude-opus-5-5",
  changesConsidered: 60,
  changesTotal: 75,
  detail: null,
  createdAt: "2026-01-01T00:00:00Z",
};

describe("InterpretationPanel", () => {
  beforeEach(() => vi.clearAllMocks());

  it("offers to generate an interpretation and shows progress", async () => {
    interpretation.mockResolvedValue(null);
    requestInterpretation.mockResolvedValue({ ...ready, status: "PENDING", significance: null, summary: null, citations: [] });
    renderWithQuery(<InterpretationPanel comparisonId={9} sectionKey="10K.ITEM_1A" />);

    await userEvent.click(await screen.findByRole("button", { name: "Explain these changes" }));

    expect(requestInterpretation).toHaveBeenCalledWith(9, "10K.ITEM_1A");
    expect(await screen.findByText("Generating the interpretation…")).toBeInTheDocument();
  });

  it("labels AI output, shows significance and links citations to change cards", async () => {
    interpretation.mockResolvedValue(ready);
    renderWithQuery(<InterpretationPanel comparisonId={9} sectionKey="10K.ITEM_1A" />);

    expect(await screen.findByText("High significance")).toBeInTheDocument();
    const panel = screen.getByRole("region", { name: "AI interpretation" });
    expect(panel).toHaveTextContent("AI interpretation");
    expect(screen.getByRole("link", { name: "[change 3]" })).toHaveAttribute("href", "#change-3");
    expect(screen.getByRole("link", { name: "[change 5]" })).toHaveAttribute("title", expect.stringContaining("not found"));
    expect(panel).toHaveTextContent("the changes themselves are the factual record");
    expect(panel).toHaveTextContent("Only the first 60 of 75 changes were considered.");
  });

  it("allows retrying a failed interpretation", async () => {
    interpretation.mockResolvedValue({ ...ready, status: "FAILED", detail: "The model is unavailable" });
    renderWithQuery(<InterpretationPanel comparisonId={9} sectionKey="10K.ITEM_1A" />);

    expect(await screen.findByText("The model is unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
