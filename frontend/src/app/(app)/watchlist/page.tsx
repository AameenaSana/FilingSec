import type { Metadata } from "next";
import { WatchlistView } from "@/components/companies/WatchlistView";

export const metadata: Metadata = { title: "Watchlist · FilingLens" };

export default function WatchlistPage() {
  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Watchlist</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          Choose which filing types to track for each company and whether to be notified of new ones.
        </p>
      </div>
      <WatchlistView />
    </div>
  );
}
