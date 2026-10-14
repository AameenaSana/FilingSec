"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { ApiError, errorMessage } from "@/lib/api/client";
import { downloadSessionMarkdown, workspaceApi, workspaceKeys, type ResearchSession } from "@/lib/api/workspace";
import { FindingCard } from "./FindingCard";

/** One research session: its description, findings and export. */
export function SessionView({ id }: { id: number }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const { data: session, error, isPending } = useQuery({
    queryKey: workspaceKeys.session(id),
    queryFn: () => workspaceApi.session(id),
  });
  const sessions = useQuery({ queryKey: workspaceKeys.sessions, queryFn: workspaceApi.sessions });
  const exportFile = useMutation({ mutationFn: () => downloadSessionMarkdown(id) });
  const remove = useMutation({
    mutationFn: () => workspaceApi.deleteSession(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workspaceKeys.all });
      router.push("/workspace");
    },
  });

  if (isPending) {
    return (
      <p role="status" className="text-sm text-zinc-500">
        Loading session…
      </p>
    );
  }
  if (error) {
    return (
      <Alert tone="error">
        {error instanceof ApiError && error.status === 404 ? "This session does not exist." : errorMessage(error)}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <Link href="/workspace" className="text-sm text-zinc-600 hover:underline dark:text-zinc-400">
        ← Workspace
      </Link>
      {editing ? (
        <EditSession session={session} onDone={() => setEditing(false)} />
      ) : (
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold tracking-tight">{session.title}</h1>
            {session.description && (
              <p className="mt-1 text-sm whitespace-pre-wrap text-zinc-600 dark:text-zinc-400">{session.description}</p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
            <Button
              type="button"
              variant="secondary"
              pending={exportFile.isPending}
              disabled={session.findings.length === 0}
              onClick={() => exportFile.mutate()}
            >
              Export Markdown
            </Button>
            <Button
              type="button"
              variant="danger"
              pending={remove.isPending}
              onClick={() => {
                if (window.confirm("Delete this session? Its findings are kept as unfiled.")) {
                  remove.mutate();
                }
              }}
            >
              Delete
            </Button>
          </div>
        </div>
      )}
      {(exportFile.error || remove.error) && <Alert tone="error">{errorMessage(exportFile.error ?? remove.error)}</Alert>}
      {session.findings.length === 0 ? (
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          No findings in this session yet. Use “Save finding” on a cited source, a filing change or a passage, and choose
          this session.
        </p>
      ) : (
        <ol className="flex flex-col gap-3">
          {session.findings.map((finding) => (
            <li key={finding.id}>
              <FindingCard finding={finding} sessions={sessions.data ?? []} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function EditSession({ session, onDone }: { session: ResearchSession; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState(session.title);
  const [description, setDescription] = useState(session.description ?? "");
  const update = useMutation({
    mutationFn: () => workspaceApi.updateSession(session.id, title.trim(), description.trim() || null),
    onSuccess: (updated) => {
      queryClient.setQueryData(workspaceKeys.session(session.id), updated);
      queryClient.invalidateQueries({ queryKey: workspaceKeys.sessions });
      onDone();
    },
  });

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (title.trim()) {
      update.mutate();
    }
  }

  return (
    <form onSubmit={onSubmit} aria-label="Edit session" className="flex max-w-2xl flex-col gap-3">
      <TextField label="Title" value={title} maxLength={200} required onChange={(e) => setTitle(e.target.value)} />
      <label htmlFor="session-description" className="text-sm font-medium">
        Description
      </label>
      <textarea
        id="session-description"
        value={description}
        maxLength={5000}
        rows={3}
        onChange={(e) => setDescription(e.target.value)}
        className="rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900"
      />
      {update.error && <Alert tone="error">{errorMessage(update.error)}</Alert>}
      <div className="flex gap-2">
        <Button type="submit" pending={update.isPending} disabled={!title.trim()}>
          Save
        </Button>
        <Button type="button" variant="secondary" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
