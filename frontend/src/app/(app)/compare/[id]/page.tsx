import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { ComparisonView } from "@/components/comparison/ComparisonView";

export const metadata: Metadata = { title: "Comparison · FilingLens" };

export default function ComparisonPage({ params }: PageProps<"/compare/[id]">) {
  return (
    <div className="max-w-6xl">
      {/* The comparison id is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading comparison…</p>}>
        {params.then(({ id }) => {
          const numericId = Number(id);
          if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            notFound();
          }
          return <ComparisonView id={numericId} />;
        })}
      </Suspense>
    </div>
  );
}
