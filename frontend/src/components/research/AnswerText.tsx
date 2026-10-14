import Link from "next/link";
import { Fragment } from "react";
import { sectionLabel } from "@/lib/api/documents";
import { formatDate } from "@/lib/api/filings";
import type { Citation } from "@/lib/api/research";

/**
 * An answer with citation markers placed after the text each citation supports,
 * followed by the list of sources. Each source links to the exact passage in the
 * filing reader.
 */
export function AnswerText({ messageId, content, citations }: { messageId: number; content: string; citations: Citation[] }) {
  const markersAt = new Map<number, number[]>();
  for (const citation of citations) {
    // A marker belongs after the text it supports; clamp offsets that overrun the text.
    const end = Math.min(citation.answerEnd, content.length);
    const ordinals = markersAt.get(end) ?? [];
    if (!ordinals.includes(citation.ordinal)) {
      ordinals.push(citation.ordinal);
    }
    markersAt.set(end, ordinals);
  }
  const boundaries = [...markersAt.keys()].filter((at) => at > 0 && at <= content.length).sort((a, b) => a - b);

  const pieces: React.ReactNode[] = [];
  let position = 0;
  for (const at of boundaries) {
    pieces.push(<Fragment key={`t${at}`}>{content.slice(position, at)}</Fragment>);
    pieces.push(
      <sup key={`m${at}`} className="ml-0.5 text-xs">
        {markersAt.get(at)!.map((ordinal) => (
          <a key={ordinal} href={`#source-${messageId}-${ordinal}`} className="text-sky-700 hover:underline dark:text-sky-400">
            [{ordinal}]
          </a>
        ))}
      </sup>,
    );
    position = at;
  }
  pieces.push(<Fragment key="rest">{content.slice(position)}</Fragment>);

  const sources = [...new Map(citations.map((c) => [c.ordinal, c])).values()].sort((a, b) => a.ordinal - b.ordinal);

  return (
    <div className="flex flex-col gap-3">
      <p className="leading-7 whitespace-pre-wrap">{pieces}</p>
      {sources.length > 0 && (
        <ol aria-label="Sources" className="flex flex-col gap-2 border-t border-zinc-200 pt-3 text-sm dark:border-zinc-800">
          {sources.map((source) => (
            <li key={source.ordinal} id={`source-${messageId}-${source.ordinal}`} className="flex gap-2">
              <span className="text-zinc-500">[{source.ordinal}]</span>
              <div className="min-w-0">
                <p className="font-medium">
                  {source.filing.companyName} {source.filing.formType}
                  {source.filing.reportDate && `, period ended ${formatDate(source.filing.reportDate)}`} ·{" "}
                  {sectionLabel({ key: source.sectionKey, title: source.sectionTitle })}
                </p>
                <blockquote className="mt-1 border-l-2 border-zinc-300 pl-2 text-zinc-700 dark:border-zinc-700 dark:text-zinc-300">
                  “{source.citedText}”
                </blockquote>
                <div className="mt-1 flex flex-wrap items-center gap-3 text-xs">
                  <Link
                    href={`/filings/${source.filing.accessionNumber}?block=${source.firstBlockOrdinal}`}
                    className="underline-offset-4 hover:underline"
                  >
                    View in filing ({source.blockRefs.join(", ")})
                  </Link>
                  {!source.verified && (
                    <span className="rounded bg-amber-100 px-1.5 py-0.5 text-amber-800 dark:bg-amber-950 dark:text-amber-300">
                      Quote not found verbatim in the source — check before relying on it
                    </span>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
