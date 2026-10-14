import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Citation } from "@/lib/api/research";
import { AnswerText } from "./AnswerText";

const filing = {
  accessionNumber: "0000320193-25-000079",
  cik: "0000320193",
  companyName: "Apple Inc.",
  formType: "10-K",
  reportDate: "2025-09-27",
  filingDate: "2025-10-31",
};

function citation(extra: Partial<Citation>): Citation {
  return {
    ordinal: 1,
    filing,
    sectionKey: "10K.ITEM_1A",
    sectionTitle: "Risk Factors",
    blockRefs: ["10K.ITEM_1A/p2"],
    firstBlockOrdinal: 41,
    citedText: "Generative AI features may not be successful.",
    answerStart: 0,
    answerEnd: 20,
    verified: true,
    ...extra,
  };
}

describe("AnswerText", () => {
  const content = "AI features may fail and tariffs may rise.";

  it("places markers after the supported text and lists linked sources", () => {
    render(
      <AnswerText
        messageId={5}
        content={content}
        citations={[
          citation({ ordinal: 1, answerStart: 0, answerEnd: 20 }),
          citation({
            ordinal: 2,
            answerStart: 21,
            answerEnd: content.length,
            blockRefs: ["10K.ITEM_1A/p3"],
            firstBlockOrdinal: 42,
            citedText: "Tariffs may rise.",
          }),
          citation({ ordinal: 1, answerStart: 21, answerEnd: content.length }),
        ]}
      />,
    );

    const paragraph = screen.getByText((_, el) => el?.tagName === "P" && el.textContent?.startsWith("AI features") === true);
    expect(paragraph.textContent).toBe("AI features may fail[1] and tariffs may rise.[2][1]");
    expect(within(paragraph).getByRole("link", { name: "[2]" })).toHaveAttribute("href", "#source-5-2");

    const sources = screen.getByRole("list", { name: "Sources" });
    const items = within(sources).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Apple Inc. 10-K, period ended Sep 27, 2025 · Item 1A · Risk Factors");
    expect(within(items[1]).getByRole("link", { name: /View in filing/ })).toHaveAttribute(
      "href",
      "/filings/0000320193-25-000079?block=42",
    );
  });

  it("flags citations whose quote was not found in the source", () => {
    render(<AnswerText messageId={5} content={content} citations={[citation({ verified: false })]} />);

    expect(screen.getByText(/Quote not found verbatim/)).toBeInTheDocument();
  });
});
