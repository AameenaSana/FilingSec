import { render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { VerifyEmail } from "./VerifyEmail";

const verifyEmail = vi.fn();
vi.mock("@/lib/api/auth", () => ({ authApi: { verifyEmail: (...args: unknown[]) => verifyEmail(...args) } }));

describe("VerifyEmail", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => window.history.replaceState(null, "", "/"));

  it("redeems the token exactly once, even under StrictMode, and strips it from the URL", async () => {
    window.history.replaceState(null, "", "/verify-email#token=abc123");
    verifyEmail.mockResolvedValue(undefined);

    render(
      <StrictMode>
        <VerifyEmail />
      </StrictMode>,
    );

    expect(await screen.findByText("Your email address is confirmed.")).toBeInTheDocument();
    expect(verifyEmail).toHaveBeenCalledTimes(1);
    expect(verifyEmail).toHaveBeenCalledWith("abc123");
    expect(window.location.hash).toBe("");
  });

  it("explains an invalid or used link", async () => {
    window.history.replaceState(null, "", "/verify-email#token=used");
    verifyEmail.mockRejectedValue(
      new ApiError(400, { type: "urn:filinglens:problem:invalid-token", detail: "This link is invalid" }),
    );

    render(<VerifyEmail />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This link is invalid");
  });

  it("reports a link without a token", async () => {
    window.history.replaceState(null, "", "/verify-email");

    render(<VerifyEmail />);

    expect(await screen.findByRole("alert")).toHaveTextContent("incomplete");
    expect(verifyEmail).not.toHaveBeenCalled();
  });
});
