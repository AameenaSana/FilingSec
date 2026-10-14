import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { finding } from "./fixtures";
import { WorkspaceView } from "./WorkspaceView";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const sessions = vi.fn();
const findings = vi.fn();
const createSession = vi.fn();
vi.mock("@/lib/api/workspace", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/workspace")>()),
  workspaceApi: {
    sessions: () => sessions(),
    findings: (...a: unknown[]) => findings(...a),
    createSession: (...a: unknown[]) => createSession(...a),
  },
}));

describe("WorkspaceView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessions.mockResolvedValue([{ id: 4, title: "AI risk", description: null, findingCount: 2, updatedAt: "" }]);
  });

  it("lists sessions and findings and filters by scope and tag", async () => {
    findings.mockResolvedValue([
      finding({ tags: ["ai"] }),
      finding({ id: 2, quotedText: "Supply chain.", tags: ["supply"] }),
    ]);
    renderWithQuery(<WorkspaceView />);

    expect(await screen.findByRole("link", { name: /AI risk/ })).toHaveAttribute("href", "/workspace/sessions/4");
    expect(await screen.findByText("Supply chain.")).toBeInTheDocument();
    expect(findings).toHaveBeenLastCalledWith({ unfiled: undefined, q: undefined, tag: undefined });

    await userEvent.click(screen.getByRole("button", { name: "Unfiled" }));
    expect(findings).toHaveBeenLastCalledWith({ unfiled: true, q: undefined, tag: undefined });

    await userEvent.click(await screen.findByRole("button", { name: "supply" }));
    expect(findings).toHaveBeenLastCalledWith({ unfiled: true, q: undefined, tag: "supply" });
  });

  it("explains how to save findings when there are none", async () => {
    findings.mockResolvedValue([]);
    renderWithQuery(<WorkspaceView />);

    expect(await screen.findByText(/No findings yet/)).toBeInTheDocument();
  });

  it("creates a session and opens it", async () => {
    findings.mockResolvedValue([]);
    createSession.mockResolvedValue({ id: 5 });
    renderWithQuery(<WorkspaceView />);

    await userEvent.type(screen.getByLabelText("New session"), "Margins");
    await userEvent.click(screen.getByRole("button", { name: "Create session" }));

    expect(createSession).toHaveBeenCalledWith("Margins");
    expect(push).toHaveBeenCalledWith("/workspace/sessions/5");
  });
});
