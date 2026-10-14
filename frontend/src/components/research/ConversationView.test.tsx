import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import type { Conversation, StreamHandlers } from "@/lib/api/research";
import { renderWithQuery } from "@/test/render";
import { ConversationView } from "./ConversationView";

const get = vi.fn();
const streamQuestion = vi.fn();
vi.mock("@/lib/api/research", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/research")>()),
  researchApi: { get: (...a: unknown[]) => get(...a) },
  streamQuestion: (...a: unknown[]) => streamQuestion(...a),
}));

const filing = {
  accessionNumber: "0000320193-25-000079",
  cik: "0000320193",
  companyName: "Apple Inc.",
  formType: "10-K",
  reportDate: "2025-09-27",
  filingDate: "2025-10-31",
};

function conversation(extra: Partial<Conversation> = {}): Conversation {
  return {
    id: 7,
    title: "Apple Inc. 10-K 2025",
    filings: [filing],
    readiness: [{ accessionNumber: filing.accessionNumber, status: "READY", semanticSearch: true }],
    messages: [],
    aiAvailable: true,
    updatedAt: "2026-01-01T00:00:00Z",
    ...extra,
  };
}

describe("ConversationView", () => {
  beforeEach(() => vi.clearAllMocks());

  it("streams an answer, then shows the stored answer with citations", async () => {
    const answered = conversation({
      messages: [
        { id: 1, role: "USER", content: "What AI risks?", status: "COMPLETE", createdAt: "", citations: [] },
        {
          id: 2,
          role: "ASSISTANT",
          content: "AI features may fail.",
          status: "COMPLETE",
          createdAt: "",
          citations: [
            {
              id: 1,
              ordinal: 1,
              filing,
              sectionKey: "10K.ITEM_1A",
              sectionTitle: "Risk Factors",
              blockRefs: ["10K.ITEM_1A/p2"],
              firstBlockOrdinal: 41,
              citedText: "Generative AI features may not be successful.",
              answerStart: 0,
              answerEnd: 21,
              verified: true,
            },
          ],
        },
      ],
    });
    get.mockResolvedValueOnce(conversation()).mockResolvedValue(answered);
    let finish: () => void = () => undefined;
    streamQuestion.mockImplementation((_id: number, _q: string, handlers: StreamHandlers) => {
      handlers.onDelta("AI features ");
      return new Promise<void>((resolve) => {
        finish = resolve;
      });
    });
    renderWithQuery(<ConversationView id={7} />);
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Question"), "What AI risks?");
    await user.click(screen.getByRole("button", { name: "Ask" }));

    expect(streamQuestion).toHaveBeenCalledWith(7, "What AI risks?", expect.anything());
    expect(await screen.findByText("AI features")).toBeInTheDocument();
    finish();
    expect(await screen.findByRole("link", { name: /View in filing/ })).toHaveAttribute(
      "href",
      "/filings/0000320193-25-000079?block=41",
    );
  });

  it("disables questions while filings are being prepared", async () => {
    get.mockResolvedValue(
      conversation({ readiness: [{ accessionNumber: filing.accessionNumber, status: "PREPARING", semanticSearch: false }] }),
    );
    renderWithQuery(<ConversationView id={7} />);

    expect(await screen.findByText(/Preparing the filings/)).toBeInTheDocument();
    expect(screen.getByLabelText("Question")).toBeDisabled();
  });

  it("explains when AI is not configured", async () => {
    get.mockResolvedValue(conversation({ aiAvailable: false }));
    renderWithQuery(<ConversationView id={7} />);

    expect(await screen.findByText(/not configured on this server/)).toBeInTheDocument();
  });

  it("asks a pre-filled question once the filings are ready", async () => {
    get.mockResolvedValue(conversation());
    streamQuestion.mockResolvedValue(undefined);
    renderWithQuery(<ConversationView id={7} initialQuestion="Compare AI risks" />);

    await waitFor(() => expect(streamQuestion).toHaveBeenCalledTimes(1));
    expect(streamQuestion).toHaveBeenCalledWith(7, "Compare AI risks", expect.anything());
  });

  it("shows errors raised before streaming and restores the question", async () => {
    get.mockResolvedValue(conversation());
    streamQuestion.mockRejectedValue(
      new ApiError(429, { type: "urn:filinglens:problem:quota-exceeded", detail: "You have reached today's question limit." }),
    );
    renderWithQuery(<ConversationView id={7} />);
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Question"), "q?");
    await user.click(screen.getByRole("button", { name: "Ask" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("today's question limit");
    await waitFor(() => expect(screen.getByLabelText("Question")).toHaveValue("q?"));
  });
});
