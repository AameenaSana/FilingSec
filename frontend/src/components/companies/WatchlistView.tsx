"use client";

import Link from "next/link";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { FORM_TYPES, type FormType, type WatchlistEntry } from "@/lib/api/companies";
import { errorMessage } from "@/lib/api/client";
import { useWatchlist, useWatchlistMutations } from "@/lib/query/useWatchlist";

export function WatchlistView() {
  const { data, error, isPending } = useWatchlist();

  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading watchlist…</p>;
  }
  if (error) {
    return <Alert tone="error">{errorMessage(error)}</Alert>;
  }
  if (data.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center dark:border-zinc-700">
        <p className="text-zinc-600 dark:text-zinc-400">You are not watching any companies yet.</p>
        <Link href="/companies" className="mt-2 inline-block text-sm font-medium underline-offset-4 hover:underline">
          Search for a company
        </Link>
      </div>
    );
  }
  return (
    <ul className="divide-y divide-zinc-200 rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
      {data.map((entry) => (
        <WatchlistRow key={entry.id} entry={entry} />
      ))}
    </ul>
  );
}

function WatchlistRow({ entry }: { entry: WatchlistEntry }) {
  const { update, remove } = useWatchlistMutations();
  const error = update.error ?? remove.error;

  function toggleForm(form: FormType) {
    const formTypes = entry.formTypes.includes(form)
      ? entry.formTypes.filter((f) => f !== form)
      : [...entry.formTypes, form];
    if (formTypes.length > 0) {
      update.mutate({ id: entry.id, formTypes, notificationsEnabled: entry.notificationsEnabled });
    }
  }

  return (
    <li className="flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <Link href={`/companies/${entry.company.cik}`} className="font-medium hover:underline">
          {entry.company.name}
        </Link>
        <p className="text-xs text-zinc-500 dark:text-zinc-400">
          {[entry.company.tickers.join(", "), `CIK ${entry.company.cik}`].filter(Boolean).join(" · ")}
        </p>
        {error && (
          <p role="alert" className="mt-1 text-xs text-red-600 dark:text-red-400">
            {errorMessage(error)}
          </p>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-4 text-sm">
        <fieldset className="flex gap-3" disabled={update.isPending}>
          <legend className="sr-only">Forms to track for {entry.company.name}</legend>
          {FORM_TYPES.map((form) => {
            const checked = entry.formTypes.includes(form);
            return (
              <label key={form} className="flex items-center gap-1.5">
                <input
                  type="checkbox"
                  checked={checked}
                  // At least one form must stay selected.
                  disabled={checked && entry.formTypes.length === 1}
                  onChange={() => toggleForm(form)}
                />
                {form}
              </label>
            );
          })}
        </fieldset>
        <label className="flex items-center gap-1.5">
          <input
            type="checkbox"
            checked={entry.notificationsEnabled}
            disabled={update.isPending}
            onChange={(e) =>
              update.mutate({ id: entry.id, formTypes: entry.formTypes, notificationsEnabled: e.target.checked })
            }
          />
          Notify me
        </label>
        <Button
          type="button"
          variant="secondary"
          className="h-8 px-3 text-xs"
          pending={remove.isPending}
          aria-label={`Remove ${entry.company.name} from watchlist`}
          onClick={() => remove.mutate(entry.id)}
        >
          Remove
        </Button>
      </div>
    </li>
  );
}
