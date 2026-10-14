"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { comparisonKeys, comparisonsApi } from "@/lib/api/comparisons";
import { formatDate } from "@/lib/api/filings";

/** Starts a period-over-period comparison of this filing with another of the same form. */
export function CompareLauncher({ accession }: { accession: string }) {
  const router = useRouter();
  const selectId = useId();
  const { data } = useQuery({
    queryKey: comparisonKeys.candidates(accession),
    queryFn: () => comparisonsApi.candidates(accession),
  });
  const [choice, setChoice] = useState<string | null>(null);
  const selected = choice ?? data?.suggested ?? data?.filings[0]?.accessionNumber ?? "";
  const compare = useMutation({
    mutationFn: () => comparisonsApi.create(selected, accession),
    onSuccess: (comparison) => router.push(`/compare/${comparison.id}`),
  });

  if (!data || data.filings.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="flex flex-col gap-1.5">
        <label htmlFor={selectId} className="text-sm font-medium">
          Compare with
        </label>
        <select
          id={selectId}
          value={selected}
          onChange={(e) => setChoice(e.target.value)}
          className="h-10 rounded-md border border-zinc-300 bg-white px-3 text-sm dark:border-zinc-700 dark:bg-zinc-900"
        >
          {data.filings.map((filing) => (
            <option key={filing.accessionNumber} value={filing.accessionNumber}>
              {filing.formType} · period {formatDate(filing.reportDate) ?? "n/a"} · filed {formatDate(filing.filingDate)}
              {filing.accessionNumber === data.suggested ? " (suggested)" : ""}
            </option>
          ))}
        </select>
      </div>
      <Button type="button" pending={compare.isPending} disabled={!selected} onClick={() => compare.mutate()}>
        Compare
      </Button>
      {compare.error && (
        <p role="alert" className="w-full text-sm text-red-600 dark:text-red-400">
          {errorMessage(compare.error)}
        </p>
      )}
    </div>
  );
}
