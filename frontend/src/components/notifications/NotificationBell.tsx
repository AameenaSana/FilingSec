"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import {
  NOTIFICATION_STREAM_URL,
  notificationKeys,
  notificationsApi,
  type AppNotification,
} from "@/lib/api/notifications";
import { NotificationList } from "./NotificationList";

const LIMIT = 8;

/**
 * Header bell with the unread count and the latest notifications. A server-sent
 * event stream keeps it current; the list is also refetched periodically in case
 * the stream is unavailable.
 */
export function NotificationBell() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [live, setLive] = useState<{ unread: number; latest?: AppNotification } | null>(null);
  const panel = useRef<HTMLDivElement>(null);
  const { data } = useQuery({
    queryKey: notificationKeys.list(false, LIMIT),
    queryFn: () => notificationsApi.list(false, LIMIT),
    refetchInterval: 120_000,
  });
  const markAll = useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });

  useEffect(() => {
    if (typeof EventSource === "undefined") {
      return;
    }
    const source = new EventSource(NOTIFICATION_STREAM_URL, { withCredentials: true });
    source.addEventListener("unread", (event) => {
      setLive({ unread: JSON.parse((event as MessageEvent).data).unreadCount });
    });
    source.addEventListener("notification", (event) => {
      const payload = JSON.parse((event as MessageEvent).data);
      setLive({ unread: payload.unreadCount, latest: payload.notification });
      queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    });
    return () => source.close();
  }, [queryClient]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === "Escape" : !panel.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  const unread = live?.unread ?? data?.unreadCount ?? 0;

  return (
    <div ref={panel} className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="true"
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
        onClick={() => setOpen((o) => !o)}
        className="relative rounded-md p-1.5 text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
      >
        <svg aria-hidden viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-4 rounded-full bg-violet-600 px-1 text-center text-[10px] leading-4 font-semibold text-white">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>
      <span role="status" aria-live="polite" className="sr-only">
        {live?.latest ? `New notification: ${live.latest.title}` : ""}
      </span>
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-96 max-w-[calc(100vw-2rem)] overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-lg dark:border-zinc-800 dark:bg-zinc-950">
          <div className="flex items-center justify-between border-b border-zinc-200 px-4 py-2 dark:border-zinc-800">
            <span className="text-sm font-semibold">Notifications</span>
            {unread > 0 && (
              <button
                type="button"
                onClick={() => markAll.mutate()}
                className="text-xs text-violet-700 hover:underline dark:text-violet-400"
              >
                Mark all read
              </button>
            )}
          </div>
          <div className="max-h-96 overflow-y-auto">
            <NotificationList items={data?.items ?? []} onNavigate={() => setOpen(false)} />
          </div>
          <Link
            href="/notifications"
            onClick={() => setOpen(false)}
            className="block border-t border-zinc-200 px-4 py-2 text-center text-sm hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-900"
          >
            See all notifications
          </Link>
        </div>
      )}
    </div>
  );
}
