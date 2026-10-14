import { describe, expect, it } from "vitest";
import { timeAgo } from "./notifications";

describe("timeAgo", () => {
  const now = new Date("2026-06-01T12:00:00Z");

  it("describes recent times relative to now", () => {
    expect(timeAgo("2026-06-01T11:59:30Z", now)).toBe("just now");
    expect(timeAgo("2026-06-01T11:45:00Z", now)).toBe("15 min ago");
    expect(timeAgo("2026-06-01T09:00:00Z", now)).toBe("3 h ago");
    expect(timeAgo("2026-05-31T09:00:00Z", now)).toBe("yesterday");
    expect(timeAgo("2026-05-20T09:00:00Z", now)).toBe("May 20, 2026");
  });
});
