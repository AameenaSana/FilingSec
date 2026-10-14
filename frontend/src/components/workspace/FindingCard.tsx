"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { sectionLabel } from "@/lib/api/documents";
import { formatDate } from "@/lib/api/filings";
import { parseTags, workspaceApi, workspaceKeys, type Finding, type SessionSummary } from "@/lib/api/workspace";

const SOURCE_LABEL: Record<Finding["sourceType"], string> = {
  CITATION: "From an answer",
  CHANGE: "Filing change",
  PASSAGE: "Passage",
};

/** A saved finding: the verbatim filing text, where it came from, and the user's note and tags. */
export function FindingCard({ finding, sessions }: { finding: Finding; sessions: SessionSummary[] }) {
  const [editing, setEditing] = useState(false);
  const queryClient = useQueryClient();
  const remove = useMutation({
    mutationFn: () => workspaceApi.deleteFinding(finding.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: workspaceKeys.all }),
  });

  return (
    <article className="rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500">
        <span className="rounded bg-zinc-100 px-1.5 py-0.5 font-medium text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
          {SOURCE_LABEL[finding.sourceType]}
          {finding.changeType && ` · ${finding.changeType.toLowerCase()}`}
        </span>
        <span className="font-medium text-zinc-800 dark:text-zinc-200">
          {finding.filing.companyName} {finding.filing.formType}
          {finding.filing.reportDate && `, period ended ${formatDate(finding.filing.reportDate)}`}
        </span>
        <span>
          {sectionLabel({
            key: finding.sectionKey,
            title: finding.sectionTitle,
          })}
        </span>
      </header>

      {finding.previousText && (
        <>
          <p className="mt-3 text-xs font-medium text-zinc-500">Earlier text</p>
          <blockquote className="mt-1 border-l-2 border-red-300 pl-3 text-sm leading-6 whitespace-pre-wrap text-zinc-600 dark:border-red-800 dark:text-zinc-400">
            {finding.previousText}
          </blockquote>
          <p className="mt-2 text-xs font-medium text-zinc-500">Later text</p>
        </>
      )}
      <blockquote
        className={`mt-2 border-l-2 pl-3 text-sm leading-6 whitespace-pre-wrap ${
          finding.previousText ? "border-emerald-400 dark:border-emerald-700" : "border-zinc-300 dark:border-zinc-700"
        }`}
      >
        {finding.quotedText}
      </blockquote>

      {editing ? (
        <EditForm finding={finding} sessions={sessions} onDone={() => setEditing(false)} />
      ) : (
        <>
          {finding.note && <p className="mt-3 text-sm whitespace-pre-wrap">{finding.note}</p>}
          {finding.tags.length > 0 && (
            <ul aria-label="Tags" className="mt-2 flex flex-wrap gap-1.5">
              {finding.tags.map((tag) => (
                <li
                  key={tag}
                  className="rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-800 dark:bg-violet-950 dark:text-violet-300"
                >
                  {tag}
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      <footer className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
        <Link
          href={`/filings/${finding.filing.accessionNumber}?block=${finding.blockOrdinal}`}
          className="underline-offset-4 hover:underline"
        >
          View in filing ({finding.blockRefs.join(", ")})
        </Link>
        {finding.previousAccession && finding.previousBlockOrdinal && (
          <Link
            href={`/filings/${finding.previousAccession}?block=${finding.previousBlockOrdinal}`}
            className="underline-offset-4 hover:underline"
          >
            View earlier text
          </Link>
        )}
        {finding.comparisonId && (
          <Link
            href={`/compare/${finding.comparisonId}?section=${encodeURIComponent(finding.sectionKey)}#change-${finding.changeOrdinal}`}
            className="underline-offset-4 hover:underline"
          >
            Open comparison
          </Link>
        )}
        {finding.conversationId && (
          <Link href={`/research/${finding.conversationId}`} className="underline-offset-4 hover:underline">
            Open conversation
          </Link>
        )}
        {!editing && (
          <>
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="text-violet-700 hover:underline dark:text-violet-400"
            >
              Edit
            </button>
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => {
                if (window.confirm("Delete this finding?")) {
                  remove.mutate();
                }
              }}
              className="text-red-700 hover:underline disabled:opacity-60 dark:text-red-400"
            >
              Delete
            </button>
          </>
        )}
        {remove.error && (
          <span role="alert" className="text-red-600">
            {errorMessage(remove.error)}
          </span>
        )}
      </footer>
    </article>
  );
}

function EditForm({ finding, sessions, onDone }: { finding: Finding; sessions: SessionSummary[]; onDone: () => void }) {
  const id = useId();
  const queryClient = useQueryClient();
  const [sessionId, setSessionId] = useState(finding.sessionId ? String(finding.sessionId) : "");
  const [note, setNote] = useState(finding.note ?? "");
  const [tags, setTags] = useState(finding.tags.join(", "));
  const update = useMutation({
    mutationFn: () =>
      workspaceApi.updateFinding(finding.id, {
        sessionId: sessionId ? Number(sessionId) : null,
        note: note.trim() ? note : null,
        tags: parseTags(tags),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workspaceKeys.all });
      onDone();
    },
  });

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    update.mutate();
  }

  return (
    <form onSubmit={onSubmit} aria-label="Edit finding" className="mt-3 flex flex-col gap-2 text-sm">
      <label htmlFor={`${id}-note`} className="text-xs font-medium">
        Note
      </label>
      <textarea
        id={`${id}-note`}
        value={note}
        maxLength={5000}
        rows={3}
        onChange={(e) => setNote(e.target.value)}
        className="rounded-md border border-zinc-300 bg-white px-2 py-1 dark:border-zinc-700 dark:bg-zinc-950"
      />
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-tags`} className="text-xs font-medium">
            Tags
          </label>
          <input
            id={`${id}-tags`}
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            className="h-9 rounded-md border border-zinc-300 bg-white px-2 dark:border-zinc-700 dark:bg-zinc-950"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-session`} className="text-xs font-medium">
            Session
          </label>
          <select
            id={`${id}-session`}
            value={sessionId}
            onChange={(e) => setSessionId(e.target.value)}
            className="h-9 rounded-md border border-zinc-300 bg-white px-2 dark:border-zinc-700 dark:bg-zinc-950"
          >
            <option value="">No session (unfiled)</option>
            {sessions.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </select>
        </div>
      </div>
      {update.error && (
        <p role="alert" className="text-xs text-red-600 dark:text-red-400">
          {errorMessage(update.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" className="h-8 px-3 text-xs" pending={update.isPending}>
          Save changes
        </Button>
        <Button type="button" variant="secondary" className="h-8 px-3 text-xs" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
