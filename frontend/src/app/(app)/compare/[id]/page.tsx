import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { ComparisonView } from "@/components/comparison/ComparisonView";

export const metadata: Metadata = { title: "Comparison · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function ComparisonPage({ params, searchParams }: PageProps<"/compare/[id]">) {
  return (
    <div className="max-w-6xl">
      {/* The comparison id is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading comparison…</p>}>
        {Promise.all([params, searchParams]).then(([{ id }, query]) => {
          const numericId = Number(id);
          if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            notFound();
          }
          // ?section= opens a section, e.g. when following a saved finding.
          const section = typeof query.section === "string" ? query.section : undefined;
          return <ComparisonView id={numericId} initialSection={section} />;
        })}
      </Suspense>
    </div>
  );
}
