"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { notificationKeys, notificationsApi } from "@/lib/api/notifications";
import { NotificationList } from "./NotificationList";

export function NotificationsView() {
  const queryClient = useQueryClient();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const { data, error, isPending } = useQuery({
    queryKey: notificationKeys.list(unreadOnly, 100),
    queryFn: () => notificationsApi.list(unreadOnly, 100),
  });
  const markAll = useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div
          role="group"
          aria-label="Show"
          className="flex overflow-hidden rounded-md border border-zinc-300 text-sm dark:border-zinc-700"
        >
          {[false, true].map((value) => (
            <button
              key={String(value)}
              type="button"
              aria-pressed={unreadOnly === value}
              onClick={() => setUnreadOnly(value)}
              className="h-9 px-3 aria-pressed:border-violet-600 aria-pressed:bg-violet-600 aria-pressed:text-white dark:aria-pressed:border-violet-500 dark:aria-pressed:bg-violet-500 dark:aria-pressed:text-white"
            >
              {value ? "Unread" : "All"}
            </button>
          ))}
        </div>
        <Button
          type="button"
          variant="secondary"
          className="h-9"
          pending={markAll.isPending}
          disabled={!data || data.unreadCount === 0}
          onClick={() => markAll.mutate()}
        >
          Mark all read
        </Button>
      </div>
      {isPending && (
        <p role="status" className="animate-pulse text-sm text-zinc-500">
          Loading notifications…
        </p>
      )}
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {data && (
        <div className="rounded-xl border border-zinc-200 bg-white shadow-xs dark:border-zinc-800 dark:bg-zinc-950">
          <NotificationList items={data.items} />
        </div>
      )}
    </div>
  );
}
