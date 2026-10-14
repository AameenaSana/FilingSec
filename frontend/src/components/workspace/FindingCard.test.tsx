import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { FindingCard } from "./FindingCard";
import { finding } from "./fixtures";

const updateFinding = vi.fn();
const deleteFinding = vi.fn();
vi.mock("@/lib/api/workspace", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/workspace")>()),
  workspaceApi: {
    updateFinding: (...a: unknown[]) => updateFinding(...a),
    deleteFinding: (...a: unknown[]) => deleteFinding(...a),
  },
}));

const SESSIONS = [{ id: 4, title: "AI risk", description: null, findingCount: 1, updatedAt: "" }];

describe("FindingCard", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows both versions of a changed passage with links to each source", () => {
    renderWithQuery(
      <FindingCard
        sessions={SESSIONS}
        finding={finding({
          sourceType: "CHANGE",
          changeType: "MODIFIED",
          quotedText: "New wording.",
          previousText: "Old wording.",
          previousAccession: "0000320193-24-000123",
          previousBlockOrdinal: 30,
          comparisonId: 9,
          changeOrdinal: 2,
          conversationId: null,
          tags: ["ai"],
        })}
      />,
    );

    expect(screen.getByText("Old wording.")).toBeInTheDocument();
    expect(screen.getByText("New wording.")).toBeInTheDocument();
    expect(screen.getByText(/Filing change · modified/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /View in filing/ })).toHaveAttribute(
      "href",
      "/filings/0000320193-25-000079?block=42",
    );
    expect(screen.getByRole("link", { name: "View earlier text" })).toHaveAttribute(
      "href",
      "/filings/0000320193-24-000123?block=30",
    );
    expect(screen.getByRole("link", { name: "Open comparison" })).toHaveAttribute(
      "href",
      "/compare/9?section=10K.ITEM_1A#change-2",
    );
    expect(screen.getByRole("list", { name: "Tags" })).toHaveTextContent("ai");
  });

  it("edits the note, tags and session", async () => {
    updateFinding.mockResolvedValue(finding());
    renderWithQuery(<FindingCard sessions={SESSIONS} finding={finding({ note: "first" })} />);

    await userEvent.click(screen.getByRole("button", { name: "Edit" }));
    const note = screen.getByLabelText("Note");
    await userEvent.clear(note);
    await userEvent.type(note, "revised");
    await userEvent.type(screen.getByLabelText("Tags"), "Margins");
    await userEvent.selectOptions(screen.getByLabelText("Session"), "4");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(updateFinding).toHaveBeenCalledWith(1, { sessionId: 4, note: "revised", tags: ["margins"] });
    expect(await screen.findByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("deletes after confirmation", async () => {
    deleteFinding.mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    renderWithQuery(<FindingCard sessions={SESSIONS} finding={finding()} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(deleteFinding).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(deleteFinding).toHaveBeenCalledWith(1);
  });
});
