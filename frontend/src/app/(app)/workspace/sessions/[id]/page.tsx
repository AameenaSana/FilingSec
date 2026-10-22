import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { SessionView } from "@/components/workspace/SessionView";

export const metadata: Metadata = { title: "Research session · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function SessionPage({ params }: PageProps<"/workspace/sessions/[id]">) {
  return (
    <div className="max-w-4xl">
      {/* The session id is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading session…</p>}>
        {params.then(({ id }) => {
          const numericId = Number(id);
          if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            notFound();
          }
          return <SessionView id={numericId} />;
        })}
      </Suspense>
    </div>
  );
}
