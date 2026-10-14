import type { Metadata } from "next";
import { DashboardView } from "@/components/dashboard/DashboardView";

export const metadata: Metadata = { title: "Dashboard · FilingLens" };

export default function DashboardPage() {
  return <DashboardView />;
}
