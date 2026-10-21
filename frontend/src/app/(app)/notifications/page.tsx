import type { Metadata } from "next";
import { NotificationsView } from "@/components/notifications/NotificationsView";

export const metadata: Metadata = { title: "Notifications · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function NotificationsPage() {
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          New filings and comparisons for the companies on your watchlist.
        </p>
      </div>
      <NotificationsView />
    </div>
  );
}
