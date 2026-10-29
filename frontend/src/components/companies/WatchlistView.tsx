"use client";

import Link from "next/link";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Skeleton } from "@/components/ui/Skeleton";
import { FORM_TYPES, type FormType, type WatchlistEntry } from "@/lib/api/companies";
import { errorMessage } from "@/lib/api/client";
import { useWatchlist, useWatchlistMutations } from "@/lib/query/useWatchlist";

export function WatchlistView() {
  const { data, error, isPending } = useWatchlist();

  if (isPending) {
    return (
      <div role="status" className="flex flex-col gap-2">
        <span className="sr-only">Loading watchlist…</span>
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-20 rounded-xl" />
        ))}
      </div>
    );
  }
  if (error) {
    return <Alert tone="error">{errorMessage(error)}</Alert>;
  }
  if (data.length === 0) {
    return (
      <div className="flex animate-fade-in flex-col items-center rounded-xl border border-dashed border-zinc-300 p-10 text-center dark:border-zinc-700">
        <span className="mb-3 grid size-10 place-items-center rounded-full bg-violet-100 text-violet-700 dark:bg-violet-500/15 dark:text-violet-300">
          <Icon name="star" className="size-5" />
        </span>
        <p className="text-zinc-600 dark:text-zinc-400">You are not watching any companies yet.</p>
        <Link
          href="/companies"
          className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-violet-600 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-violet-500 active:scale-95"
        >
          Search for a company
        </Link>
      </div>
    );
  }
  return (
    <ul className="divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-xs dark:divide-zinc-800 dark:border-zinc-800 dark:bg-zinc-950">
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
    <li className="flex animate-fade-in-up flex-col gap-3 px-4 py-4 transition-colors hover:bg-zinc-50/70 sm:flex-row sm:items-center sm:justify-between dark:hover:bg-zinc-900/40">
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
        <fieldset className="flex gap-1.5" disabled={update.isPending}>
          <legend className="sr-only">Forms to track for {entry.company.name}</legend>
          {FORM_TYPES.map((form) => {
            const checked = entry.formTypes.includes(form);
            return (
              <label key={form} className="relative">
                <input
                  type="checkbox"
                  className="peer sr-only"
                  checked={checked}
                  // At least one form must stay selected.
                  disabled={checked && entry.formTypes.length === 1}
                  onChange={() => toggleForm(form)}
                />
                <span className="flex cursor-pointer items-center gap-1 rounded-full border border-zinc-300 px-2.5 py-1 text-xs font-medium text-zinc-600 transition select-none peer-checked:border-violet-600 peer-checked:bg-violet-600 peer-checked:text-white peer-focus-visible:ring-4 peer-focus-visible:ring-violet-500/25 peer-disabled:cursor-not-allowed peer-disabled:opacity-70 hover:border-zinc-400 active:scale-95 dark:border-zinc-700 dark:text-zinc-400 dark:peer-checked:border-violet-500 dark:peer-checked:bg-violet-500 dark:peer-checked:text-white">
                  {checked && <Icon name="check" className="size-3" strokeWidth="2.5" />}
                  {form}
                </span>
              </label>
            );
          })}
        </fieldset>
        <label className="flex cursor-pointer items-center gap-2 select-none">
          <input
            type="checkbox"
            className="peer sr-only"
            checked={entry.notificationsEnabled}
            disabled={update.isPending}
            onChange={(e) =>
              update.mutate({ id: entry.id, formTypes: entry.formTypes, notificationsEnabled: e.target.checked })
            }
          />
          <span
            aria-hidden
            className="relative h-5 w-9 rounded-full bg-zinc-300 transition-colors peer-checked:bg-violet-600 peer-focus-visible:ring-4 peer-focus-visible:ring-violet-500/25 peer-disabled:opacity-60 after:absolute after:top-0.5 after:left-0.5 after:size-4 after:rounded-full after:bg-white after:shadow after:transition-transform peer-checked:after:translate-x-4 dark:bg-zinc-700"
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
