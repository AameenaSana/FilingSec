import type { Metadata } from "next";
import { Suspense } from "react";
import { FilingDetailView } from "@/components/filings/FilingDetailView";

export const metadata: Metadata = { title: "Filing · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function FilingPage({ params }: PageProps<"/filings/[accession]">) {
  return (
    <div className="max-w-5xl">
      {/* The accession number is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading filing…</p>}>
        {params.then(({ accession }) => (
          <FilingDetailView accession={accession} />
        ))}
      </Suspense>
    </div>
  );
}
