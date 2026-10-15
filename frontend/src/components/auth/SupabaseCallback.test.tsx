import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { SupabaseCallback } from "./SupabaseCallback";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));

const setUser = vi.fn();
vi.mock("@/lib/auth/AuthProvider", () => ({ useAuth: () => ({ setUser }) }));

const supabaseSignIn = vi.fn();
vi.mock("@/lib/api/auth", () => ({
  authApi: { supabaseSignIn: (...args: unknown[]) => supabaseSignIn(...args) },
}));

const exchangeCodeForSession = vi.fn();
const signOut = vi.fn();
let next: string | null = null;
vi.mock("@/lib/auth/supabase", () => ({
  supabaseConfigured: true,
  supabase: () => ({ auth: { exchangeCodeForSession, signOut } }),
  takeNext: () => next,
}));

const account = {
  id: "1",
  email: "a@example.com",
  displayName: "Analyst",
  role: "USER",
  emailVerified: true,
  createdAt: "2026-01-01T00:00:00Z",
};

function visit(query: string) {
  window.history.replaceState(null, "", `/auth/callback${query}`);
  render(<SupabaseCallback />);
}

describe("SupabaseCallback", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    next = null;
    signOut.mockResolvedValue({ error: null });
  });

  it("exchanges the code, starts a FilingLens session and drops the Supabase one", async () => {
    exchangeCodeForSession.mockResolvedValue({ data: { session: { access_token: "jwt" } }, error: null });
    supabaseSignIn.mockResolvedValue(account);
    next = "/watchlist";

    visit("?code=abc");

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/watchlist"));
    expect(exchangeCodeForSession).toHaveBeenCalledWith("abc");
    expect(supabaseSignIn).toHaveBeenCalledWith("jwt");
    expect(setUser).toHaveBeenCalledWith(account);
    expect(signOut).toHaveBeenCalledWith({ scope: "local" });
  });

  it("ignores an unsafe return path", async () => {
    exchangeCodeForSession.mockResolvedValue({ data: { session: { access_token: "jwt" } }, error: null });
    supabaseSignIn.mockResolvedValue(account);
    next = "//evil.example";

    visit("?code=abc");

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
  });

  it("reports a cancelled sign-in without echoing text from the URL", async () => {
    visit("?error=access_denied&error_description=Call+this+number");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Google sign-in was cancelled");
    expect(alert).not.toHaveTextContent("Call this number");
    expect(exchangeCodeForSession).not.toHaveBeenCalled();
  });

  it("still discards the Supabase session when the backend refuses it", async () => {
    exchangeCodeForSession.mockResolvedValue({ data: { session: { access_token: "jwt" } }, error: null });
    supabaseSignIn.mockRejectedValue(
      new ApiError(409, { detail: "This email address is already linked to a different sign-in." }),
    );

    visit("?code=abc");

    expect(await screen.findByRole("alert")).toHaveTextContent("already linked");
    expect(signOut).toHaveBeenCalledWith({ scope: "local" });
    expect(replace).not.toHaveBeenCalled();
  });
});
