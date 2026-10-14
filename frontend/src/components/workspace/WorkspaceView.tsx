"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useDeferredValue, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { errorMessage } from "@/lib/api/client";
import { workspaceApi, workspaceKeys, type FindingFilter } from "@/lib/api/workspace";
import { FindingCard } from "./FindingCard";

/** Research sessions and a searchable list of all saved findings. */
export function WorkspaceView() {
  const sessions = useQuery({ queryKey: workspaceKeys.sessions, queryFn: workspaceApi.sessions });
  const [scope, setScope] = useState<"all" | "unfiled">("all");
  const [text, setText] = useState("");
  const [tag, setTag] = useState<string | null>(null);
  const q = useDeferredValue(text.trim());
  const filter: FindingFilter = { unfiled: scope === "unfiled" || undefined, q: q || undefined, tag: tag ?? undefined };
  const findings = useQuery({ queryKey: workspaceKeys.findings(filter), queryFn: () => workspaceApi.findings(filter) });

  return (
    <div className="grid gap-8 lg:grid-cols-[18rem_1fr]">
      <aside className="flex flex-col gap-4">
        <h2 className="text-sm font-semibold tracking-wide text-zinc-500 uppercase">Sessions</h2>
        {sessions.error && <Alert tone="error">{errorMessage(sessions.error)}</Alert>}
        {sessions.data && sessions.data.length === 0 && (
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            No sessions yet. Create one to group findings for a research question.
          </p>
        )}
        {sessions.data && sessions.data.length > 0 && (
          <ul className="flex flex-col gap-1">
            {sessions.data.map((session) => (
              <li key={session.id}>
                <Link
                  href={`/workspace/sessions/${session.id}`}
                  className="flex items-baseline justify-between gap-2 rounded px-2 py-1.5 text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
                >
                  <span className="truncate">{session.title}</span>
                  <span className="shrink-0 text-xs text-zinc-500">{session.findingCount}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <NewSessionForm />
      </aside>

      <section aria-label="Findings" className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-wrap items-end gap-3">
          <TextField
            label="Search findings"
            type="search"
            value={text}
            maxLength={200}
            onChange={(e) => setText(e.target.value)}
            className="min-w-56 flex-1"
          />
          <div role="group" aria-label="Show" className="flex overflow-hidden rounded-md border border-zinc-300 text-sm dark:border-zinc-700">
            {(["all", "unfiled"] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={scope === value}
                onClick={() => setScope(value)}
                className="h-10 px-3 aria-pressed:bg-zinc-900 aria-pressed:text-white dark:aria-pressed:bg-zinc-100 dark:aria-pressed:text-zinc-900"
              >
                {value === "all" ? "All" : "Unfiled"}
              </button>
            ))}
          </div>
        </div>
        {tag && (
          <p className="text-sm">
            Tagged <span className="font-medium">{tag}</span> ·{" "}
            <button type="button" onClick={() => setTag(null)} className="text-violet-700 hover:underline dark:text-violet-400">
              clear
            </button>
          </p>
        )}
        {findings.isPending && (
          <p role="status" className="text-sm text-zinc-500">
            Loading findings…
          </p>
        )}
        {findings.error && <Alert tone="error">{errorMessage(findings.error)}</Alert>}
        {findings.data && findings.data.length === 0 && (
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            {q || tag || scope === "unfiled"
              ? "No findings match."
              : "No findings yet. Save a cited source from an answer, a change from a comparison, or a passage from a filing."}
          </p>
        )}
        {findings.data && findings.data.length > 0 && (
          <>
            <TagFilter tags={findings.data.flatMap((f) => f.tags)} selected={tag} onSelect={setTag} />
            <ol className="flex flex-col gap-3">
              {findings.data.map((finding) => (
                <li key={finding.id}>
                  <FindingCard finding={finding} sessions={sessions.data ?? []} />
                </li>
              ))}
            </ol>
          </>
        )}
      </section>
    </div>
  );
}

function TagFilter({ tags, selected, onSelect }: { tags: string[]; selected: string | null; onSelect: (tag: string) => void }) {
  const unique = [...new Set(tags)].filter((t) => t !== selected).sort();
  if (unique.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-xs">
      <span className="text-zinc-500">Filter by tag:</span>
      {unique.map((tag) => (
        <button
          key={tag}
          type="button"
          onClick={() => onSelect(tag)}
          className="rounded-full border border-violet-200 px-2 py-0.5 text-violet-800 hover:bg-violet-50 dark:border-violet-900 dark:text-violet-300 dark:hover:bg-violet-950"
        >
          {tag}
        </button>
      ))}
    </div>
  );
}

function NewSessionForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const create = useMutation({
    mutationFn: () => workspaceApi.createSession(title.trim()),
    onSuccess: (session) => {
      queryClient.invalidateQueries({ queryKey: workspaceKeys.sessions });
      router.push(`/workspace/sessions/${session.id}`);
    },
  });

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (title.trim()) {
      create.mutate();
    }
  }

  return (
    <form onSubmit={onSubmit} aria-label="Create a session" className="flex flex-col gap-2">
      <TextField
        label="New session"
        placeholder="e.g. AI risk disclosures, FY2025"
        value={title}
        maxLength={200}
        onChange={(e) => setTitle(e.target.value)}
        error={create.error ? errorMessage(create.error) : undefined}
      />
      <Button type="submit" variant="secondary" pending={create.isPending} disabled={!title.trim()}>
        Create session
      </Button>
    </form>
  );
}
