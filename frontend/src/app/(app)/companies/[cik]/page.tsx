import type { Metadata } from "next";
import { Suspense } from "react";
import { CompanyDetailView } from "@/components/companies/CompanyDetailView";

export const metadata: Metadata = { title: "Company · FilingLens" };

export default function CompanyPage({ params }: PageProps<"/companies/[cik]">) {
  return (
    <div className="max-w-4xl">
      {/* The CIK is only known at request time. */}
      <Suspense fallback={<p className="text-sm text-zinc-500">Loading company…</p>}>
        {params.then(({ cik }) => (
          <CompanyDetailView cik={cik} />
        ))}
      </Suspense>
    </div>
  );
}
