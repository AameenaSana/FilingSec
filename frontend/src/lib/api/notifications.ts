import { API_BASE, apiFetch } from "./client";

export interface AppNotification {
  id: number;
  type: "NEW_FILING" | "COMPARISON_READY";
  title: string;
  body: string;
  /** Path within the app. */
  link: string;
  createdAt: string;
  read: boolean;
}

export interface NotificationPage {
  unreadCount: number;
  items: AppNotification[];
}

export interface NotificationPreferences {
  emailEnabled: boolean;
}

export const notificationsApi = {
  list: (unreadOnly = false, limit = 30) =>
    apiFetch<NotificationPage>(`/notifications?unreadOnly=${unreadOnly}&limit=${limit}`),
  markRead: (id: number) => apiFetch<void>(`/notifications/${id}/read`, { method: "POST" }),
  markAllRead: () => apiFetch<void>("/notifications/read-all", { method: "POST" }),
  preferences: () => apiFetch<NotificationPreferences>("/notification-preferences"),
  updatePreferences: (preferences: NotificationPreferences) =>
    apiFetch<NotificationPreferences>("/notification-preferences", {
      method: "PUT",
      body: JSON.stringify(preferences),
    }),
};

export const notificationKeys = {
  all: ["notifications"] as const,
  list: (unreadOnly: boolean, limit: number) => ["notifications", "list", unreadOnly, limit] as const,
  preferences: ["notifications", "preferences"] as const,
};

export const NOTIFICATION_STREAM_URL = `${API_BASE}/notifications/stream`;

/** "5 min ago", "yesterday", or a date for older notifications. */
export function timeAgo(iso: string, now: Date = new Date()): string {
  const seconds = Math.max(0, (now.getTime() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  if (seconds < 172800) return "yesterday";
  return new Date(iso).toLocaleDateString("en-US", { dateStyle: "medium" });
}
