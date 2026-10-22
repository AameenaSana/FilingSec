import type { Metadata } from "next";
import { DashboardView } from "@/components/dashboard/DashboardView";

export const metadata: Metadata = { title: "Dashboard · FilingLens" };

// Rendered only after sign-in is confirmed in the browser; see (app)/layout.tsx.
export const instant = false;

export default function DashboardPage() {
  return <DashboardView />;
}
