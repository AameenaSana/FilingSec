import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { BackendStatus } from "./BackendStatus";

describe("BackendStatus", () => {
  it("shows the API version when the backend is reachable", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ name: "FilingLens", version: "0.1.0", serverTime: "2026-01-01T00:00:00Z" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    render(<BackendStatus />);

    expect(screen.getByRole("status")).toHaveTextContent("Checking API");
    expect(await screen.findByText("API connected · v0.1.0")).toBeInTheDocument();
  });

  it("reports an unreachable backend", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    render(<BackendStatus />);

    expect(await screen.findByText("API unreachable")).toBeInTheDocument();
  });
});
