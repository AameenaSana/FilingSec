import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { ConversationView } from "@/components/research/ConversationView";

export const metadata: Metadata = { title: "Research · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function ConversationPage({ params, searchParams }: PageProps<"/research/[id]">) {
  return (
    <div className="max-w-4xl">
      {/* The conversation id is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading conversation…</p>}>
        {Promise.all([params, searchParams]).then(([{ id }, query]) => {
          const numericId = Number(id);
          if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            notFound();
          }
          // ?q= carries a question to ask as soon as the filings are ready (from "Compare companies").
          const initialQuestion = typeof query.q === "string" ? query.q.slice(0, 2000) : undefined;
          return <ConversationView id={numericId} initialQuestion={initialQuestion} />;
        })}
      </Suspense>
    </div>
  );
}
