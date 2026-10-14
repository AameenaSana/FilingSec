import type { Metadata } from "next";
import { CrossCompanyForm } from "@/components/research/CrossCompanyForm";

export const metadata: Metadata = { title: "Compare companies · FilingLens" };

export default function CompareCompaniesPage() {
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Compare companies</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          Ask how several companies address the same topic in their filings. Each company&apos;s latest filing of the
          chosen form is used, and the answer cites each company&apos;s own filing.
        </p>
      </div>
      <CrossCompanyForm />
    </div>
  );
}
