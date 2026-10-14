"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth/AuthProvider";
import { useWatchlist } from "@/lib/query/useWatchlist";

/** Interim dashboard until the full dashboard is built (Phase 10). */
export function DashboardWelcome() {
  const { state } = useAuth();
  const { data: watchlist } = useWatchlist();
  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Welcome, {state.user?.displayName}</h1>
      <section className="rounded-lg border border-zinc-200 p-6 dark:border-zinc-800">
        <h2 className="font-medium">Your watchlist</h2>
        {watchlist && watchlist.length > 0 ? (
          <ul className="mt-3 flex flex-wrap gap-2">
            {watchlist.map((entry) => (
              <li key={entry.id}>
                <Link
                  href={`/companies/${entry.company.cik}`}
                  className="inline-block rounded-full border border-zinc-300 px-3 py-1 text-sm hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-900"
                >
                  {entry.company.tickers[0] ?? entry.company.name}
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">
            <Link href="/companies" className="font-medium underline-offset-4 hover:underline">
              Search for companies
            </Link>{" "}
            to start tracking their filings.
          </p>
        )}
      </section>
    </div>
  );
}
