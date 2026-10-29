"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useRef, useState, type KeyboardEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Icon, Spinner } from "@/components/ui/Icon";
import { companiesApi, queryKeys } from "@/lib/api/companies";
import { errorMessage } from "@/lib/api/client";
import { useDebounced } from "@/lib/useDebounced";
import { WatchButton } from "./WatchButton";

const DEBOUNCE_MS = 250;

/** Wraps the first case-insensitive occurrence of {@code query} in a highlight. */
function Highlight({ text, query }: { text: string; query: string }) {
  const at = query ? text.toLowerCase().indexOf(query.toLowerCase()) : -1;
  if (at < 0) {
    return text;
  }
  return (
    <>
      {text.slice(0, at)}
      <mark className="rounded-sm bg-violet-100 px-0.5 text-inherit dark:bg-violet-500/25">
        {text.slice(at, at + query.length)}
      </mark>
      {text.slice(at + query.length)}
    </>
  );
}

export function CompanySearch() {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const results = useRef<HTMLUListElement>(null);
  const [value, setValue] = useState("");
  const query = useDebounced(value.trim(), DEBOUNCE_MS);
  const { data, error, isFetching } = useQuery({
    queryKey: queryKeys.companySearch(query),
    queryFn: () => companiesApi.search(query),
    enabled: query.length > 0,
    placeholderData: (previous) => previous,
  });

  function clear() {
    setValue("");
    input.current?.focus();
  }

  // Arrow keys move between the search box and the result links.
  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape" && value) {
      clear();
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") {
      return;
    }
    const links = [...(results.current?.querySelectorAll<HTMLAnchorElement>("a[data-result]") ?? [])];
    if (links.length === 0) {
      return;
    }
    event.preventDefault();
    const current = links.indexOf(document.activeElement as HTMLAnchorElement);
    const next = current + (event.key === "ArrowDown" ? 1 : -1);
    if (next < 0) {
      input.current?.focus();
    } else {
      links[Math.min(next, links.length - 1)].focus();
    }
  }

  return (
    <div className="flex flex-col gap-4" onKeyDown={onKeyDown}>
      <label htmlFor={inputId} className="sr-only">
        Search companies
      </label>
      <div className="group relative">
        <span className="pointer-events-none absolute inset-y-0 left-4 flex items-center text-zinc-400 transition-colors group-focus-within:text-violet-500">
          {isFetching ? <Spinner className="size-4" /> : <Icon name="search" className="size-4" />}
        </span>
        <input
          ref={input}
          id={inputId}
          type="search"
          autoFocus
          autoComplete="off"
          maxLength={100}
          placeholder="Company name, ticker or CIK"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          className="h-12 w-full rounded-xl border border-zinc-300 bg-white pr-11 pl-11 text-base shadow-xs transition outline-none hover:border-zinc-400 focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:border-zinc-600 [&::-webkit-search-cancel-button]:hidden"
        />
        {value && (
          <button
            type="button"
            onClick={clear}
            aria-label="Clear search"
            className="absolute inset-y-0 right-3 my-auto grid size-7 animate-scale-in place-items-center rounded-md text-zinc-400 hover:bg-zinc-100 hover:text-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-zinc-200"
          >
            <Icon name="close" className="size-4" />
          </button>
        )}
      </div>
      {!query && (
        <p className="text-xs text-zinc-500">
          Try <SearchHint onPick={setValue}>Apple</SearchHint>, <SearchHint onPick={setValue}>MSFT</SearchHint> or{" "}
          <SearchHint onPick={setValue}>0001018724</SearchHint>. Use ↑ ↓ to move through results.
        </p>
      )}
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {query && data && data.length === 0 && !isFetching && (
        <div className="animate-fade-in rounded-xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-600 dark:border-zinc-700 dark:text-zinc-400">
          No companies match &ldquo;{query}&rdquo;.
        </div>
      )}
      {query && data && data.length > 0 && (
        <ul
          ref={results}
          aria-label="Search results"
          className={`divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-xs transition-opacity dark:divide-zinc-800 dark:border-zinc-800 dark:bg-zinc-950 ${
            isFetching ? "opacity-60" : ""
          }`}
        >
          {data.map((company, index) => (
            <li
              key={company.cik}
              style={{ animationDelay: `${index * 30}ms` }}
              className="group flex animate-fade-in-up items-center justify-between gap-4 px-4 py-3 transition-colors focus-within:bg-violet-50/60 hover:bg-zinc-50 dark:focus-within:bg-violet-500/10 dark:hover:bg-zinc-900/60"
            >
              <Link
                data-result
                href={`/companies/${company.cik}`}
                className="flex min-w-0 flex-1 items-center gap-3 rounded-md outline-none"
              >
                <span
                  aria-hidden
                  className="grid size-9 shrink-0 place-items-center rounded-lg bg-zinc-100 text-[10px] font-bold text-zinc-600 transition group-hover:bg-violet-100 group-hover:text-violet-700 dark:bg-zinc-800 dark:text-zinc-300 dark:group-hover:bg-violet-500/15 dark:group-hover:text-violet-300"
                >
                  {(company.tickers[0] ?? company.name).slice(0, 4).toUpperCase()}
                </span>
                <span className="min-w-0">
                  <span className="block truncate font-medium group-hover:underline">
                    <Highlight text={company.name} query={query} />
                  </span>
                  <span className="text-xs text-zinc-500 dark:text-zinc-400">
                    {[company.tickers.join(", "), company.exchange, `CIK ${company.cik}`].filter(Boolean).join(" · ")}
                  </span>
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

function SearchHint({ children, onPick }: { children: string; onPick: (value: string) => void }) {
  return (
    <button
      type="button"
      onClick={() => onPick(children)}
      className="rounded-md bg-zinc-100 px-1.5 py-0.5 font-medium text-zinc-700 transition hover:bg-violet-100 hover:text-violet-700 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-violet-500/15 dark:hover:text-violet-300"
    >
      {children}
    </button>
  );
}
