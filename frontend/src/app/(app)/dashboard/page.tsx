import type { Metadata } from "next";
import { DashboardWelcome } from "./DashboardWelcome";

export const metadata: Metadata = { title: "Dashboard · FilingLens" };

export default function DashboardPage() {
  return <DashboardWelcome />;
}
