import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { CrossCompanyForm } from "./CrossCompanyForm";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const search = vi.fn();
vi.mock("@/lib/api/companies", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/companies")>()),
  companiesApi: { search: (...a: unknown[]) => search(...a) },
}));

const createCrossCompany = vi.fn();
vi.mock("@/lib/api/research", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/research")>()),
  researchApi: { createCrossCompany: (...a: unknown[]) => createCrossCompany(...a) },
}));

const apple = { cik: "0000320193", name: "Apple Inc.", tickers: ["AAPL"], exchange: "Nasdaq" };
const microsoft = { cik: "0000789019", name: "Microsoft Corp", tickers: ["MSFT"], exchange: "Nasdaq" };

describe("CrossCompanyForm", () => {
  beforeEach(() => vi.clearAllMocks());

  it("picks companies and opens a conversation that asks the question", async () => {
    search.mockImplementation(async (q: string) => (q.startsWith("app") ? [apple] : [microsoft]));
    createCrossCompany.mockResolvedValue({ id: 12 });
    renderWithQuery(<CrossCompanyForm />);
    const user = userEvent.setup();

    const picker = screen.getByLabelText("Add a company");
    await user.type(picker, "apple");
    await user.click(await screen.findByRole("button", { name: /Apple Inc\./ }));
    await user.type(picker, "micro");
    await user.click(await screen.findByRole("button", { name: /Microsoft Corp/ }));

    const compare = screen.getByRole("button", { name: "Compare" });
    expect(compare).toBeDisabled();
    await user.type(screen.getByLabelText("Fiscal year (optional)"), "2025");
    await user.type(screen.getByLabelText("Topic (optional)"), "AI risks");
    await user.type(screen.getByLabelText("Question"), "How does each describe AI risks?");
    await user.click(compare);

    expect(createCrossCompany).toHaveBeenCalledWith(["0000320193", "0000789019"], "10-K", 2025, "AI risks");
    expect(push).toHaveBeenCalledWith(`/research/12?q=${encodeURIComponent("How does each describe AI risks?")}`);
  });

  it("removes a selected company", async () => {
    search.mockResolvedValue([apple]);
    renderWithQuery(<CrossCompanyForm />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Add a company"), "apple");
    await user.click(await screen.findByRole("button", { name: /Apple Inc\./ }));
    await user.click(screen.getByRole("button", { name: "Remove Apple Inc." }));

    expect(screen.queryByRole("button", { name: "Remove Apple Inc." })).not.toBeInTheDocument();
  });
});
