"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Alert } from "@/components/ui/Alert";
import { errorMessage } from "@/lib/api/client";
import { researchApi, researchKeys } from "@/lib/api/research";

export function ConversationList() {
  const { data, error, isPending } = useQuery({ queryKey: researchKeys.list, queryFn: researchApi.list });
  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading conversations…</p>;
  }
  if (error) {
    return <Alert tone="error">{errorMessage(error)}</Alert>;
  }
  if (data.length === 0) {
    return (
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        No conversations yet. Open a filing and choose “Ask about this filing”.
      </p>
    );
  }
  return (
    <ul className="divide-y divide-zinc-200 rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
      {data.map((conversation) => (
        <li key={conversation.id} className="px-4 py-3">
          <Link href={`/research/${conversation.id}`} className="font-medium hover:underline">
            {conversation.title}
          </Link>
          <p className="text-xs text-zinc-500">
            {conversation.filings.map((f) => `${f.companyName} ${f.formType}`).join(", ")} · updated{" "}
            {new Date(conversation.updatedAt).toLocaleDateString("en-US", { dateStyle: "medium" })}
          </p>
        </li>
      ))}
    </ul>
  );
}
