"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useId, useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { companiesApi, queryKeys } from "@/lib/api/companies";
import { errorMessage } from "@/lib/api/client";
import { WatchButton } from "./WatchButton";

const DEBOUNCE_MS = 250;

function useDebounced(value: string, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

export function CompanySearch() {
  const inputId = useId();
  const [input, setInput] = useState("");
  const query = useDebounced(input.trim(), DEBOUNCE_MS);
  const { data, error, isFetching } = useQuery({
    queryKey: queryKeys.companySearch(query),
    queryFn: () => companiesApi.search(query),
    enabled: query.length > 0,
    placeholderData: (previous) => previous,
  });

  return (
    <div className="flex flex-col gap-4">
      <label htmlFor={inputId} className="sr-only">
        Search companies
      </label>
      <input
        id={inputId}
        type="search"
        autoFocus
        autoComplete="off"
        maxLength={100}
        placeholder="Company name, ticker or CIK"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        className="h-11 rounded-md border border-zinc-300 bg-white px-4 text-base outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-200 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:ring-zinc-800"
      />
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {query && data && data.length === 0 && !isFetching && (
        <p className="text-sm text-zinc-600 dark:text-zinc-400">No companies match &ldquo;{query}&rdquo;.</p>
      )}
      {query && data && data.length > 0 && (
        <ul aria-label="Search results" className="divide-y divide-zinc-200 rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {data.map((company) => (
            <li key={company.cik} className="flex items-center justify-between gap-4 px-4 py-3">
              <Link href={`/companies/${company.cik}`} className="min-w-0 flex-1 hover:underline">
                <span className="block truncate font-medium">{company.name}</span>
                <span className="text-xs text-zinc-500 dark:text-zinc-400">
                  {[company.tickers.join(", "), company.exchange, `CIK ${company.cik}`].filter(Boolean).join(" · ")}
                </span>
              </Link>
              <WatchButton cik={company.cik} companyName={company.name} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
