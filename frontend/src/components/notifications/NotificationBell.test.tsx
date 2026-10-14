import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AppNotification } from "@/lib/api/notifications";
import { renderWithQuery } from "@/test/render";
import { NotificationBell } from "./NotificationBell";

const list = vi.fn();
const markRead = vi.fn();
const markAllRead = vi.fn();
vi.mock("@/lib/api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/notifications")>()),
  notificationsApi: {
    list: (...a: unknown[]) => list(...a),
    markRead: (...a: unknown[]) => markRead(...a),
    markAllRead: () => markAllRead(),
  },
}));

/** Minimal EventSource stand-in that tests can push events through. */
class FakeEventSource {
  static last: FakeEventSource;
  readonly url: string;
  closed = false;
  private listeners = new Map<string, (event: MessageEvent) => void>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.last = this;
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, listener);
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.(new MessageEvent(type, { data: JSON.stringify(data) }));
  }

  close() {
    this.closed = true;
  }
}

const FILING: AppNotification = {
  id: 9,
  type: "NEW_FILING",
  title: "Apple Inc. filed a 10-K",
  body: "Apple Inc. filed a 10-K on Oct 31, 2025.",
  link: "/filings/0000320193-25-000079",
  createdAt: new Date().toISOString(),
  read: false,
};

describe("NotificationBell", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("EventSource", FakeEventSource);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the unread count and the latest notifications", async () => {
    list.mockResolvedValue({ unreadCount: 1, items: [FILING] });
    markRead.mockResolvedValue(undefined);
    renderWithQuery(<NotificationBell />);

    const bell = await screen.findByRole("button", { name: "Notifications, 1 unread" });
    await userEvent.click(bell);

    const link = screen.getByRole("link", { name: /Apple Inc. filed a 10-K/ });
    expect(link).toHaveAttribute("href", "/filings/0000320193-25-000079");
    await userEvent.click(link);
    expect(markRead).toHaveBeenCalledWith(9);
  });

  it("updates live from the event stream", async () => {
    list.mockResolvedValue({ unreadCount: 0, items: [] });
    const { unmount } = renderWithQuery(<NotificationBell />);
    await screen.findByRole("button", { name: "Notifications" });
    expect(FakeEventSource.last.url).toBe("/api/v1/notifications/stream");

    act(() => FakeEventSource.last.emit("notification", { notification: FILING, unreadCount: 3 }));

    expect(screen.getByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("New notification: Apple Inc. filed a 10-K");

    act(() => FakeEventSource.last.emit("unread", { unreadCount: 0 }));
    expect(screen.getByRole("button", { name: "Notifications" })).toBeInTheDocument();

    unmount();
    expect(FakeEventSource.last.closed).toBe(true);
  });

  it("marks everything read", async () => {
    list.mockResolvedValue({ unreadCount: 1, items: [FILING] });
    markAllRead.mockResolvedValue(undefined);
    renderWithQuery(<NotificationBell />);

    await userEvent.click(await screen.findByRole("button", { name: "Notifications, 1 unread" }));
    await userEvent.click(screen.getByRole("button", { name: "Mark all read" }));

    expect(markAllRead).toHaveBeenCalled();
  });
});
