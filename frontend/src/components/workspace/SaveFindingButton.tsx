"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { parseTags, workspaceApi, workspaceKeys, type FindingSource } from "@/lib/api/workspace";

/**
 * Saves a citation, change or passage as a finding, optionally filed in a
 * research session with a note and tags.
 */
export function SaveFindingButton({ source, label = "Save finding" }: { source: FindingSource; label?: string }) {
  const [open, setOpen] = useState(false);
  const [savedId, setSavedId] = useState<number | null>(null);

  if (savedId !== null) {
    return (
      <span className="text-xs text-emerald-700 dark:text-emerald-400">
        Saved ·{" "}
        <Link href="/workspace" className="underline-offset-4 hover:underline">
          Open workspace
        </Link>
      </span>
    );
  }
  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="text-xs text-violet-700 hover:underline dark:text-violet-400"
      >
        {label}
      </button>
    );
  }
  return <SaveForm source={source} onCancel={() => setOpen(false)} onSaved={setSavedId} />;
}

function SaveForm({
  source,
  onCancel,
  onSaved,
}: {
  source: FindingSource;
  onCancel: () => void;
  onSaved: (id: number) => void;
}) {
  const id = useId();
  const queryClient = useQueryClient();
  const sessions = useQuery({
    queryKey: workspaceKeys.sessions,
    queryFn: workspaceApi.sessions,
  });
  const [sessionId, setSessionId] = useState("");
  const [note, setNote] = useState("");
  const [tags, setTags] = useState("");
  const save = useMutation({
    mutationFn: () =>
      workspaceApi.saveFinding(source, {
        sessionId: sessionId ? Number(sessionId) : null,
        note,
        tags: parseTags(tags),
      }),
    onSuccess: (finding) => {
      queryClient.invalidateQueries({ queryKey: workspaceKeys.all });
      onSaved(finding.id);
    },
  });

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    save.mutate();
  }

  return (
    <form
      onSubmit={onSubmit}
      aria-label="Save finding"
      className="mt-2 flex w-full flex-col gap-2 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-sm dark:border-zinc-800 dark:bg-zinc-900"
    >
      <label htmlFor={`${id}-session`} className="text-xs font-medium">
        Research session
      </label>
      <select
        id={`${id}-session`}
        value={sessionId}
        onChange={(e) => setSessionId(e.target.value)}
        className="h-9 rounded-md border border-zinc-300 bg-white px-2 dark:border-zinc-700 dark:bg-zinc-950"
      >
        <option value="">No session (unfiled)</option>
        {sessions.data?.map((s) => (
          <option key={s.id} value={s.id}>
            {s.title}
          </option>
        ))}
      </select>
      <label htmlFor={`${id}-note`} className="text-xs font-medium">
        Note
      </label>
      <textarea
        id={`${id}-note`}
        value={note}
        maxLength={5000}
        rows={2}
        onChange={(e) => setNote(e.target.value)}
        className="rounded-md border border-zinc-300 bg-white px-2 py-1 dark:border-zinc-700 dark:bg-zinc-950"
      />
      <label htmlFor={`${id}-tags`} className="text-xs font-medium">
        Tags <span className="font-normal text-zinc-500">(comma-separated)</span>
      </label>
      <input
        id={`${id}-tags`}
        value={tags}
        onChange={(e) => setTags(e.target.value)}
        className="h-9 rounded-md border border-zinc-300 bg-white px-2 dark:border-zinc-700 dark:bg-zinc-950"
      />
      {save.error && (
        <p role="alert" className="text-xs text-red-600 dark:text-red-400">
          {errorMessage(save.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" className="h-8 px-3 text-xs" pending={save.isPending}>
          Save
        </Button>
        <Button type="button" variant="secondary" className="h-8 px-3 text-xs" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
