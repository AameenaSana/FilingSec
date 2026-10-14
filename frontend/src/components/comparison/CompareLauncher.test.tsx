import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { CompareLauncher } from "./CompareLauncher";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const candidates = vi.fn();
const create = vi.fn();
vi.mock("@/lib/api/comparisons", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/comparisons")>()),
  comparisonsApi: { candidates: (...a: unknown[]) => candidates(...a), create: (...a: unknown[]) => create(...a) },
}));

const CURRENT = "0000320193-25-000079";

describe("CompareLauncher", () => {
  beforeEach(() => vi.clearAllMocks());

  it("preselects the suggested filing and opens the new comparison", async () => {
    candidates.mockResolvedValue({
      suggested: "0000320193-24-000123",
      filings: [
        { accessionNumber: "0000320193-25-000001", formType: "10-K/A", filingDate: "2025-01-15", reportDate: "2024-09-28" },
        { accessionNumber: "0000320193-24-000123", formType: "10-K", filingDate: "2024-11-01", reportDate: "2024-09-28" },
      ],
    });
    create.mockResolvedValue({ id: 9 });
    renderWithQuery(<CompareLauncher accession={CURRENT} />);

    const select = await screen.findByLabelText("Compare with");
    expect(select).toHaveValue("0000320193-24-000123");
    expect(screen.getByRole("option", { name: /\(suggested\)/ })).toHaveValue("0000320193-24-000123");

    await userEvent.click(screen.getByRole("button", { name: "Compare" }));

    expect(create).toHaveBeenCalledWith("0000320193-24-000123", CURRENT);
    expect(push).toHaveBeenCalledWith("/compare/9");
  });

  it("renders nothing when there is no other filing of this form", async () => {
    candidates.mockResolvedValue({ suggested: null, filings: [] });
    const { container } = renderWithQuery(<CompareLauncher accession={CURRENT} />);

    await vi.waitFor(() => expect(candidates).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
