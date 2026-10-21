import type { Metadata } from "next";
import { SettingsView } from "./SettingsView";

export const metadata: Metadata = { title: "Settings · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function SettingsPage() {
  return <SettingsView />;
}
