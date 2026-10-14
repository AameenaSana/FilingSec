import type { Metadata } from "next";
import { ConversationList } from "@/components/research/ConversationList";

export const metadata: Metadata = { title: "Research · FilingLens" };

export default function ResearchPage() {
  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Research</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          Your questions about filings, with answers cited to the source passages.
        </p>
      </div>
      <ConversationList />
    </div>
  );
}
