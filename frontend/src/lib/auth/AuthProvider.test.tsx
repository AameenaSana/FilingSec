import { act, render, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import type { Account } from "@/lib/api/auth";
import { AuthProvider, useAuth } from "./AuthProvider";

vi.mock("@/lib/api/auth", () => ({
  authApi: { me: () => Promise.reject(new Error("401")), logout: () => Promise.resolve() },
}));

const account: Account = {
  id: "1",
  email: "a@example.com",
  displayName: "Analyst",
  role: "USER",
  emailVerified: true,
  createdAt: "2026-01-01T00:00:00Z",
};

describe("AuthProvider", () => {
  it("keeps its actions stable when the auth state changes", async () => {
    const seen: ReturnType<typeof useAuth>[] = [];
    function Probe() {
      seen.push(useAuth());
      return null;
    }
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider>
          <Probe />
        </AuthProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(seen.at(-1)?.state.status).toBe("anonymous"));

    // Effects that list these as dependencies (e.g. the Google sign-in callback)
    // must not re-run, and loop, because signing in changed the state.
    const before = seen.at(-1)!;
    act(() => before.setUser(account));
    const after = seen.at(-1)!;

    expect(after.state.status).toBe("authenticated");
    expect(after.setUser).toBe(before.setUser);
    expect(after.signOut).toBe(before.signOut);
    expect(after.clear).toBe(before.clear);
    expect(after.refresh).toBe(before.refresh);
  });
});
