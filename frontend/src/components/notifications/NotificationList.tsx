"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { notificationKeys, notificationsApi, timeAgo, type AppNotification } from "@/lib/api/notifications";

/** Notifications, newest first; opening one marks it read. */
export function NotificationList({ items, onNavigate }: { items: AppNotification[]; onNavigate?: () => void }) {
  const queryClient = useQueryClient();
  const markRead = useMutation({
    mutationFn: (id: number) => notificationsApi.markRead(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });

  if (items.length === 0) {
    return (
      <p className="px-4 py-6 text-sm text-zinc-600 dark:text-zinc-400">
        No notifications. You are notified when a company on your watchlist files a new report.
      </p>
    );
  }
  return (
    <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
      {items.map((n) => (
        <li key={n.id}>
          <Link
            href={n.link}
            onClick={() => {
              if (!n.read) {
                markRead.mutate(n.id);
              }
              onNavigate?.();
            }}
            className="flex gap-3 px-4 py-3 hover:bg-zinc-50 dark:hover:bg-zinc-900"
          >
            <span
              aria-hidden
              className={`mt-1.5 size-2 shrink-0 rounded-full ${n.read ? "bg-transparent" : "bg-violet-600"}`}
            />
            <span className="min-w-0">
              <span className={`block text-sm ${n.read ? "" : "font-semibold"}`}>
                {n.title}
                {!n.read && <span className="sr-only"> (unread)</span>}
              </span>
              <span className="block text-sm text-zinc-600 dark:text-zinc-400">{n.body}</span>
              <span className="block text-xs text-zinc-500">{timeAgo(n.createdAt)}</span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
