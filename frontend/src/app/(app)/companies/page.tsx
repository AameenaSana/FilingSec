import type { Metadata } from "next";
import { CompanySearch } from "@/components/companies/CompanySearch";

export const metadata: Metadata = { title: "Companies · FilingLens" };

export default function CompaniesPage() {
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Companies</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          Search SEC-registered public companies and add them to your watchlist.
        </p>
      </div>
      <CompanySearch />
    </div>
  );
}
