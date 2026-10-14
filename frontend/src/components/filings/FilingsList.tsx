"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { FORM_TYPES, type FormType } from "@/lib/api/companies";
import { errorMessage } from "@/lib/api/client";
import { describeItem, filingKeys, filingsApi, formatDate } from "@/lib/api/filings";

const FILTERS: { label: string; forms: FormType[] }[] = [
  { label: "All", forms: [] },
  ...FORM_TYPES.map((form) => ({ label: form, forms: [form] })),
];

/** A company's 10-K, 10-Q and 8-K filings, newest first, with a form filter. */
export function FilingsList({ cik }: { cik: string }) {
  const [filter, setFilter] = useState(0);
  const forms = FILTERS[filter].forms;
  const { data, error, isPending, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: filingKeys.list(cik, forms),
    queryFn: ({ pageParam }) => filingsApi.list(cik, forms, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
  const filings = data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section aria-labelledby="filings-heading" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="filings-heading" className="text-lg font-semibold">
          Filings
        </h2>
        <div role="group" aria-label="Filter by form" className="flex gap-1">
          {FILTERS.map((option, index) => (
            <button
              key={option.label}
              type="button"
              aria-pressed={filter === index}
              onClick={() => setFilter(index)}
              className="rounded-full border border-zinc-300 px-3 py-1 text-xs aria-pressed:border-zinc-900 aria-pressed:bg-zinc-900 aria-pressed:text-white dark:border-zinc-700 dark:aria-pressed:border-zinc-100 dark:aria-pressed:bg-zinc-100 dark:aria-pressed:text-zinc-900"
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      {data?.pages[0]?.stale && (
        <Alert>SEC EDGAR could not be reached, so the most recent filings may be missing.</Alert>
      )}
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {isPending && <p role="status" className="text-sm text-zinc-500">Loading filings…</p>}
      {!isPending && !error && filings.length === 0 && (
        <p className="text-sm text-zinc-600 dark:text-zinc-400">No filings of this type were found.</p>
      )}

      {filings.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
          <table className="w-full text-left text-sm">
            <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500 dark:bg-zinc-900 dark:text-zinc-400">
              <tr>
                <th scope="col" className="px-4 py-2">Form</th>
                <th scope="col" className="px-4 py-2">Filed</th>
                <th scope="col" className="px-4 py-2">Period</th>
                <th scope="col" className="px-4 py-2">Details</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {filings.map((filing) => (
                <tr key={filing.accessionNumber}>
                  <td className="px-4 py-2 font-medium whitespace-nowrap">
                    <Link href={`/filings/${filing.accessionNumber}`} className="hover:underline">
                      {filing.formType}
                    </Link>
                  </td>
                  <td className="px-4 py-2 whitespace-nowrap">{formatDate(filing.filingDate)}</td>
                  <td className="px-4 py-2 whitespace-nowrap">{formatDate(filing.reportDate) ?? "—"}</td>
                  <td className="px-4 py-2 text-zinc-600 dark:text-zinc-400">
                    {filing.items.length > 0 ? filing.items.map(describeItem).join("; ") : filing.description}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasNextPage && (
        <div>
          <Button type="button" variant="secondary" pending={isFetchingNextPage} onClick={() => fetchNextPage()}>
            Load more
          </Button>
        </div>
      )}
    </section>
  );
}
