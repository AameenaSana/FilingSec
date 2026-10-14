"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { Alert } from "@/components/ui/Alert";
import { Card } from "@/components/ui/Card";
import { errorMessage } from "@/lib/api/client";
import { notificationKeys, notificationsApi, type NotificationPreferences } from "@/lib/api/notifications";

export function NotificationSettings() {
  const queryClient = useQueryClient();
  const { data, error } = useQuery({ queryKey: notificationKeys.preferences, queryFn: notificationsApi.preferences });
  const update = useMutation({
    mutationFn: (preferences: NotificationPreferences) => notificationsApi.updatePreferences(preferences),
    onSuccess: (preferences) => queryClient.setQueryData(notificationKeys.preferences, preferences),
  });

  return (
    <Card
      title="Notifications"
      description={
        <>
          You are notified in the app when a company on your{" "}
          <Link href="/watchlist" className="underline underline-offset-4">
            watchlist
          </Link>{" "}
          files a report of a form you follow, and when its new 10-K or 10-Q has been compared with the previous period.
          Notifications for each company can be turned off on the watchlist.
        </>
      }
    >
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {data && (
        <label className="flex items-center gap-3 text-sm">
          <input
            type="checkbox"
            checked={update.isPending ? (update.variables?.emailEnabled ?? data.emailEnabled) : data.emailEnabled}
            disabled={update.isPending}
            onChange={(e) => update.mutate({ emailEnabled: e.target.checked })}
            className="size-4"
          />
          Also send notifications by email
        </label>
      )}
      {update.error && (
        <div className="mt-3">
          <Alert tone="error">{errorMessage(update.error)}</Alert>
        </div>
      )}
    </Card>
  );
}
