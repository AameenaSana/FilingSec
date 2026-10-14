"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useRef } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { ApiError, errorMessage } from "@/lib/api/client";
import {
  describeItem,
  filingKeys,
  filingsApi,
  formatBytes,
  formatDate,
  type FilingDetail,
  type FilingStatus,
} from "@/lib/api/filings";

const POLL_MS = 2000;

const STATUS_LABEL: Record<FilingStatus, string> = {
  DISCOVERED: "Not downloaded",
  FETCHING: "Downloading from SEC…",
  FETCHED: "Stored",
  FAILED: "Download failed",
};

export function FilingDetailView({ accession }: { accession: string }) {
  const queryClient = useQueryClient();
  const { data: filing, error, isPending } = useQuery({
    queryKey: filingKeys.detail(accession),
    queryFn: () => filingsApi.get(accession),
    // Poll while SEC documents are being downloaded in the background.
    refetchInterval: (query) => (query.state.data?.status === "FETCHING" ? POLL_MS : false),
  });
  const download = useMutation({
    mutationFn: () => filingsApi.requestFetch(accession),
    onSuccess: (detail) => queryClient.setQueryData(filingKeys.detail(accession), detail),
  });

  // Documents are downloaded on first view; this happens once per page visit.
  const requested = useRef(false);
  useEffect(() => {
    if (filing?.status === "DISCOVERED" && !requested.current) {
      requested.current = true;
      download.mutate();
    }
  }, [filing?.status, download]);

  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading filing…</p>;
  }
  if (error) {
    return (
      <Alert tone="error">
        {error instanceof ApiError && error.status === 404 ? "This filing was not found." : errorMessage(error)}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href={`/companies/${filing.company.cik}`}
          className="text-sm text-zinc-600 underline-offset-4 hover:underline dark:text-zinc-400"
        >
          {filing.company.name}
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">
          Form {filing.formType}
          {filing.reportDate && <span className="font-normal"> · period ended {formatDate(filing.reportDate)}</span>}
        </h1>
        <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
          <a href={filing.filingIndexUrl} target="_blank" rel="noopener noreferrer" className="underline-offset-4 hover:underline">
            Filing index on SEC ↗
          </a>
          {filing.primaryDocumentUrl && (
            <a
              href={filing.primaryDocumentUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="underline-offset-4 hover:underline"
            >
              Original document on SEC ↗
            </a>
          )}
        </div>
      </div>

      <Metadata filing={filing} />

      {filing.items.length > 0 && (
        <section>
          <h2 className="mb-2 font-semibold">Items reported</h2>
          <ul className="list-inside list-disc text-sm">
            {filing.items.map((item) => (
              <li key={item}>{describeItem(item)}</li>
            ))}
          </ul>
        </section>
      )}

      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-semibold">Documents</h2>
          <span role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
            {STATUS_LABEL[filing.status]}
          </span>
        </div>
        {filing.status === "FAILED" && (
          <Alert tone="error">
            <div className="flex items-center justify-between gap-3">
              <span>{filing.statusDetail ?? "The documents could not be downloaded."}</span>
              <Button type="button" variant="secondary" className="h-8 px-3 text-xs" pending={download.isPending} onClick={() => download.mutate()}>
                Try again
              </Button>
            </div>
          </Alert>
        )}
        {download.error && <Alert tone="error">{errorMessage(download.error)}</Alert>}
        <DocumentsTable filing={filing} />
      </section>
    </div>
  );
}

function Metadata({ filing }: { filing: FilingDetail }) {
  const facts: [string, string | null][] = [
    ["Accession number", filing.accessionNumber],
    ["Filed", formatDate(filing.filingDate)],
    ["Accepted", filing.acceptanceAt ? new Date(filing.acceptanceAt).toLocaleString("en-US", { timeZone: "UTC" }) + " UTC" : null],
    ["Period of report", formatDate(filing.reportDate)],
    ["SEC file number", filing.fileNumber],
    ["Size", formatBytes(filing.sizeBytes)],
    ["XBRL", filing.inlineXbrl ? "Inline XBRL" : filing.xbrl ? "XBRL" : "None"],
  ];
  return (
    <dl className="grid grid-cols-1 gap-x-8 gap-y-3 rounded-lg border border-zinc-200 p-6 sm:grid-cols-2 lg:grid-cols-3 dark:border-zinc-800">
      {facts
        .filter(([, value]) => value)
        .map(([label, value]) => (
          <div key={label}>
            <dt className="text-xs uppercase tracking-wide text-zinc-500 dark:text-zinc-400">{label}</dt>
            <dd className="mt-0.5 text-sm break-all">{value}</dd>
          </div>
        ))}
    </dl>
  );
}

function DocumentsTable({ filing }: { filing: FilingDetail }) {
  if (filing.documents.length === 0) {
    return (
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        {filing.status === "FETCHING" || filing.status === "DISCOVERED"
          ? "The document list will appear once it has been read from SEC."
          : "No documents are listed for this filing."}
      </p>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
      <table className="w-full text-left text-sm">
        <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500 dark:bg-zinc-900 dark:text-zinc-400">
          <tr>
            <th scope="col" className="px-4 py-2">#</th>
            <th scope="col" className="px-4 py-2">Type</th>
            <th scope="col" className="px-4 py-2">Document</th>
            <th scope="col" className="px-4 py-2">Size</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
          {filing.documents.map((doc) => (
            <tr key={doc.filename}>
              <td className="px-4 py-2 text-zinc-500">{doc.sequence}</td>
              <td className="px-4 py-2 whitespace-nowrap">{doc.type ?? "—"}</td>
              <td className="px-4 py-2">
                <a href={doc.sourceUrl} target="_blank" rel="noopener noreferrer" className="font-medium hover:underline">
                  {doc.filename}
                </a>
                {doc.primary && (
                  <span className="ml-2 rounded bg-zinc-100 px-1.5 py-0.5 text-xs dark:bg-zinc-800">primary</span>
                )}
                {doc.stored && (
                  <span title={doc.sha256 ? `SHA-256 ${doc.sha256}` : undefined} className="ml-2 text-xs text-emerald-700 dark:text-emerald-400">
                    stored
                  </span>
                )}
                {doc.description && doc.description !== doc.type && (
                  <span className="block text-xs text-zinc-500 dark:text-zinc-400">{doc.description}</span>
                )}
              </td>
              <td className="px-4 py-2 whitespace-nowrap">{formatBytes(doc.sizeBytes) ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
