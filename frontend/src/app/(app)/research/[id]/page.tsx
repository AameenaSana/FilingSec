import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { ConversationView } from "@/components/research/ConversationView";

export const metadata: Metadata = { title: "Research · FilingLens" };

export default function ConversationPage({ params }: PageProps<"/research/[id]">) {
  return (
    <div className="max-w-4xl">
      {/* The conversation id is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading conversation…</p>}>
        {params.then(({ id }) => {
          const numericId = Number(id);
          if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            notFound();
          }
          return <ConversationView id={numericId} />;
        })}
      </Suspense>
    </div>
  );
}
