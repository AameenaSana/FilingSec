"use client";

import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { useWatchedCiks, useWatchlistMutations } from "@/lib/query/useWatchlist";

/** Adds a company to, or removes it from, the user's watchlist. */
export function WatchButton({ cik, companyName }: { cik: string; companyName: string }) {
  const watched = useWatchedCiks();
  const { add, remove } = useWatchlistMutations();
  const itemId = watched?.get(cik);
  const pending = add.isPending || remove.isPending;
  const error = add.error ?? remove.error;

  return (
    <div className="flex flex-col items-end gap-1">
      <Button
        type="button"
        variant={itemId ? "secondary" : "primary"}
        className="h-8 px-3 text-xs"
        pending={pending}
        disabled={watched === undefined}
        aria-pressed={itemId !== undefined}
        aria-label={itemId ? `Stop watching ${companyName}` : `Watch ${companyName}`}
        onClick={() => (itemId ? remove.mutate(itemId) : add.mutate(cik))}
      >
        {itemId ? "Watching" : "Watch"}
      </Button>
      {error && (
        <span role="alert" className="text-xs text-red-600 dark:text-red-400">
          {errorMessage(error)}
        </span>
      )}
    </div>
  );
}
