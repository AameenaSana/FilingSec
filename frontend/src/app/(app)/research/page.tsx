import type { Metadata } from "next";
import Link from "next/link";
import { ConversationList } from "@/components/research/ConversationList";

export const metadata: Metadata = { title: "Research · FilingLens" };

export default function ResearchPage() {
  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Research</h1>
          <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
            Your questions about filings, with answers cited to the source passages.
          </p>
        </div>
        <Link
          href="/research/compare-companies"
          className="inline-flex h-10 items-center rounded-md bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900"
        >
          Compare companies
        </Link>
      </div>
      <ConversationList />
    </div>
  );
}
