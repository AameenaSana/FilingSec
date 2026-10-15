import type { Metadata } from "next";
import { SupabaseCallback } from "@/components/auth/SupabaseCallback";
import { AuthHeading } from "../../AuthHeading";

export const metadata: Metadata = { title: "Signing in · FilingLens", referrer: "no-referrer" };

export default function AuthCallbackPage() {
  return (
    <>
      <AuthHeading title="Signing in" />
      <SupabaseCallback />
    </>
  );
}
