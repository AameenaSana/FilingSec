"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { ApiError, errorMessage } from "@/lib/api/client";
import {
  blockLink,
  comparisonKeys,
  comparisonsApi,
  type Comparison,
  type ComparedFiling,
  type DiffOp,
  type SectionSummary,
  type TextChange,
} from "@/lib/api/comparisons";
import { sectionLabel } from "@/lib/api/documents";
import { InterpretationPanel } from "./InterpretationPanel";
import { formatDate } from "@/lib/api/filings";

const POLL_MS = 3000;

const periodOf = (filing: ComparedFiling) =>
  filing.reportDate ? `period ended ${formatDate(filing.reportDate)}` : `filed ${formatDate(filing.filingDate)}`;

export function ComparisonView({ id }: { id: number }) {
  const { data: comparison, error, isPending } = useQuery({
    queryKey: comparisonKeys.detail(id),
    queryFn: () => comparisonsApi.get(id),
    refetchInterval: (query) =>
      query.state.data?.status === "WAITING" || query.state.data?.status === "COMPUTING" ? POLL_MS : false,
  });

  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading comparison…</p>;
  }
  if (error) {
    return (
      <Alert tone="error">
        {error instanceof ApiError && error.status === 404 ? "This comparison was not found." : errorMessage(error)}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <Header comparison={comparison} />
      {comparison.status === "WAITING" && (
        <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
          Downloading and reading both filings from SEC…
        </p>
      )}
      {comparison.status === "COMPUTING" && (
        <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
          Comparing the filings…
        </p>
      )}
      {comparison.status === "FAILED" && (
        <Alert tone="error">{comparison.detail ?? "The comparison could not be completed."}</Alert>
      )}
      {comparison.status === "READY" && <Results comparison={comparison} />}
    </div>
  );
}

function Header({ comparison }: { comparison: Comparison }) {
  return (
    <div>
      <Link
        href={`/companies/${comparison.company.cik}`}
        className="text-sm text-zinc-600 underline-offset-4 hover:underline dark:text-zinc-400"
      >
        {comparison.company.name}
      </Link>
      <h1 className="text-2xl font-semibold tracking-tight">Form {comparison.target.formType.replace("/A", "")} comparison</h1>
      <p className="mt-1 text-sm">
        <Link href={`/filings/${comparison.base.accessionNumber}`} className="underline-offset-4 hover:underline">
          {comparison.base.formType}, {periodOf(comparison.base)}
        </Link>
        {" → "}
        <Link href={`/filings/${comparison.target.accessionNumber}`} className="underline-offset-4 hover:underline">
          {comparison.target.formType}, {periodOf(comparison.target)}
        </Link>
      </p>
    </div>
  );
}

function Results({ comparison }: { comparison: Comparison }) {
  const changed = comparison.sections.filter((s) => s.added + s.removed + s.modified + s.moved > 0);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [includeNumericOnly, setIncludeNumericOnly] = useState(true);
  const selected = comparison.sections.find((s) => s.key === selectedKey) ?? changed[0] ?? comparison.sections[0];
  const stats = comparison.stats!;

  return (
    <>
      <Alert>
        These are the exact textual differences between the two filings, found by comparing their text section by
        section. They are not an interpretation of what the changes mean.
      </Alert>
      <dl className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        <Stat label="Added" value={stats.added} tone="text-emerald-700 dark:text-emerald-400" />
        <Stat label="Removed" value={stats.removed} tone="text-red-700 dark:text-red-400" />
        <Stat label="Modified" value={stats.modified} tone="text-amber-700 dark:text-amber-400" />
        <Stat label="Moved" value={stats.moved} tone="text-sky-700 dark:text-sky-400" />
        <Stat label="Unchanged" value={stats.unchanged} tone="text-zinc-500" />
      </dl>

      <div className="grid gap-6 lg:grid-cols-[18rem_1fr]">
        <nav aria-label="Sections" className="lg:max-h-[75vh] lg:overflow-y-auto">
          <ol className="flex flex-col gap-0.5 text-sm">
            {comparison.sections.map((section) => (
              <li key={section.key}>
                <button
                  type="button"
                  aria-current={selected?.key === section.key ? "true" : undefined}
                  onClick={() => setSelectedKey(section.key)}
                  className="flex w-full items-start justify-between gap-2 rounded px-2 py-1.5 text-left hover:bg-zinc-100 aria-[current=true]:bg-zinc-200 dark:hover:bg-zinc-800 dark:aria-[current=true]:bg-zinc-800"
                >
                  <span>
                    {sectionLabel(section)}
                    {section.alignment !== "MATCHED" && (
                      <span className="ml-1 text-xs text-zinc-500">
                        ({section.alignment === "ADDED" ? "new section" : "section removed"})
                      </span>
                    )}
                  </span>
                  <SectionCounts section={section} />
                </button>
              </li>
            ))}
          </ol>
        </nav>

        <section aria-label="Changes" className="flex min-w-0 flex-col gap-3">
          <label className="flex items-center gap-2 self-end text-sm text-zinc-600 dark:text-zinc-400">
            <input type="checkbox" checked={includeNumericOnly} onChange={(e) => setIncludeNumericOnly(e.target.checked)} />
            Include changes that differ only in numbers ({stats.numericOnly})
          </label>
          {selected && selected.added + selected.removed + selected.modified + selected.moved > 0 && (
            <InterpretationPanel key={selected.key} comparisonId={comparison.id} sectionKey={selected.key} />
          )}
          {selected && (
            <SectionChanges comparison={comparison} section={selected} includeNumericOnly={includeNumericOnly} />
          )}
        </section>
      </div>
    </>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dd className={`text-lg font-semibold ${tone}`}>{value}</dd>
      <dt className="text-zinc-600 dark:text-zinc-400">{label}</dt>
    </div>
  );
}

function SectionCounts({ section }: { section: SectionSummary }) {
  const parts = [
    section.added && `+${section.added}`,
    section.removed && `−${section.removed}`,
    section.modified && `~${section.modified}`,
    section.moved && `↔${section.moved}`,
  ].filter(Boolean);
  return <span className="shrink-0 text-xs text-zinc-500">{parts.length ? parts.join(" ") : "no changes"}</span>;
}

function SectionChanges({
  comparison,
  section,
  includeNumericOnly,
}: {
  comparison: Comparison;
  section: SectionSummary;
  includeNumericOnly: boolean;
}) {
  const { data: changes, error, isPending } = useQuery({
    queryKey: comparisonKeys.changes(comparison.id, section.key, includeNumericOnly),
    queryFn: () => comparisonsApi.changes(comparison.id, section.key, includeNumericOnly),
  });
  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading changes…</p>;
  }
  if (error) {
    return <Alert tone="error">{errorMessage(error)}</Alert>;
  }
  if (changes.length === 0) {
    return <p className="text-sm text-zinc-600 dark:text-zinc-400">No changes in this section.</p>;
  }
  return (
    <ol className="flex flex-col gap-3">
      {changes.map((change) => (
        <li key={change.ordinal}>
          <ChangeCard change={change} comparison={comparison} />
        </li>
      ))}
    </ol>
  );
}

const TYPE_STYLE: Record<TextChange["type"], { label: string; className: string }> = {
  ADDED: { label: "Added", className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300" },
  REMOVED: { label: "Removed", className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300" },
  MODIFIED: { label: "Modified", className: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300" },
  MOVED: { label: "Moved", className: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300" },
};

function ChangeCard({ change, comparison }: { change: TextChange; comparison: Comparison }) {
  const style = TYPE_STYLE[change.type];
  return (
    <article id={`change-${change.ordinal}`} className="scroll-mt-4 rounded-lg border border-zinc-200 p-4 target:ring-2 target:ring-violet-400 dark:border-zinc-800">
      <header className="mb-2 flex flex-wrap items-center gap-2 text-xs">
        <span className="text-zinc-500">#{change.ordinal}</span>
        <span className={`rounded px-1.5 py-0.5 font-medium ${style.className}`}>{style.label}</span>
        {change.numericOnly && <span className="text-zinc-500">numbers only</span>}
        {change.type === "MOVED" && change.base && <span className="text-zinc-500">from {change.base.ref}</span>}
      </header>
      <div className="text-sm leading-6 whitespace-pre-wrap">
        {change.type === "MODIFIED" && change.diff ? (
          <DiffText ops={change.diff} />
        ) : change.type === "REMOVED" ? (
          <del className="bg-red-50 text-red-900 decoration-red-400 dark:bg-red-950/40 dark:text-red-200">
            {change.base?.text}
          </del>
        ) : (
          <ins className="bg-emerald-50 text-emerald-900 no-underline dark:bg-emerald-950/40 dark:text-emerald-200">
            {change.target?.text}
          </ins>
        )}
      </div>
      <footer className="mt-3 flex flex-wrap gap-4 text-xs">
        {change.base && (
          <Link href={blockLink(comparison.base.accessionNumber, change.base.ordinal)} className="underline-offset-4 hover:underline">
            View in earlier filing ({change.base.ref})
          </Link>
        )}
        {change.target && (
          <Link
            href={blockLink(comparison.target.accessionNumber, change.target.ordinal)}
            className="underline-offset-4 hover:underline"
          >
            View in later filing ({change.target.ref})
          </Link>
        )}
      </footer>
    </article>
  );
}

function DiffText({ ops }: { ops: DiffOp[] }) {
  return (
    <>
      {ops.map((op, index) =>
        op.op === "insert" ? (
          <ins key={index} className="bg-emerald-100 text-emerald-900 no-underline dark:bg-emerald-900/50 dark:text-emerald-100">
            {op.text}
          </ins>
        ) : op.op === "delete" ? (
          <del key={index} className="bg-red-100 text-red-900 decoration-red-500 dark:bg-red-900/50 dark:text-red-100">
            {op.text}
          </del>
        ) : (
          <span key={index}>{op.text}</span>
        ),
      )}
    </>
  );
}
