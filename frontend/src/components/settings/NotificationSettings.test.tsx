import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { NotificationSettings } from "./NotificationSettings";

const preferences = vi.fn();
const updatePreferences = vi.fn();
vi.mock("@/lib/api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/notifications")>()),
  notificationsApi: {
    preferences: () => preferences(),
    updatePreferences: (...a: unknown[]) => updatePreferences(...a),
  },
}));

describe("NotificationSettings", () => {
  beforeEach(() => vi.clearAllMocks());

  it("turns email notifications off", async () => {
    preferences.mockResolvedValue({ emailEnabled: true });
    updatePreferences.mockResolvedValue({ emailEnabled: false });
    renderWithQuery(<NotificationSettings />);

    const checkbox = await screen.findByRole("checkbox", { name: "Also send notifications by email" });
    expect(checkbox).toBeChecked();
    await userEvent.click(checkbox);

    expect(updatePreferences).toHaveBeenCalledWith({ emailEnabled: false });
    await vi.waitFor(() => expect(checkbox).not.toBeChecked());
  });
});
