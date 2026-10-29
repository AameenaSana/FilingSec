"use client";

import { useQuery } from "@tanstack/react-query";
import { FilingsList } from "@/components/filings/FilingsList";
import { Alert } from "@/components/ui/Alert";
import { companiesApi, formatFiscalYearEnd, queryKeys } from "@/lib/api/companies";
import { ApiError, errorMessage } from "@/lib/api/client";
import { WatchButton } from "./WatchButton";

export function CompanyDetailView({ cik }: { cik: string }) {
  const { data: company, error, isPending } = useQuery({
    queryKey: queryKeys.company(cik),
    queryFn: () => companiesApi.get(cik),
  });

  if (isPending) {
    return <p role="status" className="animate-pulse text-sm text-zinc-500">Loading company…</p>;
  }
  if (error) {
    return (
      <Alert tone="error">
        {error instanceof ApiError && error.status === 404 ? "No company with this CIK was found." : errorMessage(error)}
      </Alert>
    );
  }

  const facts: [string, string | null][] = [
    ["CIK", company.cik],
    ["Tickers", company.tickers.join(", ") || null],
    ["Exchange", company.exchange],
    ["Industry (SIC)", company.sic ? `${company.sic}${company.sicDescription ? ` · ${company.sicDescription}` : ""}` : null],
    ["Filer category", company.category],
    ["Fiscal year end", formatFiscalYearEnd(company.fiscalYearEnd)],
    ["State of incorporation", company.stateOfIncorporation],
  ];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{company.name}</h1>
          <a
            href={company.edgarUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="text-sm text-zinc-600 underline-offset-4 hover:underline dark:text-zinc-400"
          >
            View on SEC EDGAR ↗
          </a>
        </div>
        <WatchButton cik={company.cik} companyName={company.name} />
      </div>
      {!company.profileSyncedAt && (
        <Alert>Full company details could not be loaded from SEC EDGAR yet. Showing directory information only.</Alert>
      )}
      <dl className="grid grid-cols-1 gap-x-8 gap-y-3 rounded-xl border border-zinc-200 bg-white p-6 shadow-xs transition-shadow hover:shadow-sm sm:grid-cols-2 dark:border-zinc-800 dark:bg-zinc-950">
        {facts
          .filter(([, value]) => value)
          .map(([label, value]) => (
            <div key={label}>
              <dt className="text-xs uppercase tracking-wide text-zinc-500 dark:text-zinc-400">{label}</dt>
              <dd className="mt-0.5 text-sm">{value}</dd>
            </div>
          ))}
      </dl>
      <FilingsList cik={company.cik} />
    </div>
  );
}
