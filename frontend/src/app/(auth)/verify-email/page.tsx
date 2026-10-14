import type { Metadata } from "next";
import { VerifyEmail } from "@/components/auth/VerifyEmail";
import { AuthHeading } from "../AuthHeading";

export const metadata: Metadata = { title: "Confirm email · FilingLens", referrer: "no-referrer" };

export default function VerifyEmailPage() {
  return (
    <>
      <AuthHeading title="Confirm your email" />
      <VerifyEmail />
    </>
  );
}
