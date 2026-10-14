"use client";

import { useAuth } from "@/lib/auth/AuthProvider";

/** Placeholder until the dashboard is built (Phase 10). */
export function DashboardWelcome() {
  const { state } = useAuth();
  return (
    <div className="flex flex-col gap-2">
      <h1 className="text-2xl font-semibold tracking-tight">Welcome, {state.user?.displayName}</h1>
      <p className="text-zinc-600 dark:text-zinc-400">
        Company search and your watchlist are coming next.
      </p>
    </div>
  );
}
