import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { renderWithQuery } from "@/test/render";
import { finding } from "./fixtures";
import { SaveFindingButton } from "./SaveFindingButton";

const sessions = vi.fn();
const saveFinding = vi.fn();
vi.mock("@/lib/api/workspace", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/workspace")>()),
  workspaceApi: { sessions: () => sessions(), saveFinding: (...a: unknown[]) => saveFinding(...a) },
}));

describe("SaveFindingButton", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessions.mockResolvedValue([{ id: 4, title: "AI risk", description: null, findingCount: 0, updatedAt: "" }]);
  });

  it("sends only the source reference with session, note and tags", async () => {
    saveFinding.mockResolvedValue(finding({ id: 7 }));
    renderWithQuery(<SaveFindingButton source={{ type: "CITATION", citationId: 3 }} />);

    await userEvent.click(screen.getByRole("button", { name: "Save finding" }));
    await screen.findByRole("option", { name: "AI risk" });
    await userEvent.selectOptions(screen.getByLabelText("Research session"), "4");
    await userEvent.type(screen.getByLabelText("Note"), "Compare with peers");
    await userEvent.type(screen.getByLabelText(/Tags/), "AI Risk, supply chain, ai risk");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(saveFinding).toHaveBeenCalledWith(
      { type: "CITATION", citationId: 3 },
      { sessionId: 4, note: "Compare with peers", tags: ["ai-risk", "supply-chain"] },
    );
    expect(await screen.findByText(/Saved/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open workspace" })).toHaveAttribute("href", "/workspace");
  });

  it("shows why saving failed", async () => {
    saveFinding.mockRejectedValue(new ApiError(404, { detail: "Citation 3 not found" }));
    renderWithQuery(<SaveFindingButton source={{ type: "CITATION", citationId: 3 }} />);

    await userEvent.click(screen.getByRole("button", { name: "Save finding" }));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Citation 3 not found");
  });
});
