import type { Metadata } from "next";
import { ResetPasswordForm } from "@/components/auth/ResetPasswordForm";
import { AuthHeading } from "../AuthHeading";

export const metadata: Metadata = { title: "Choose a new password · FilingLens", referrer: "no-referrer" };

export default function ResetPasswordPage() {
  return (
    <>
      <AuthHeading title="Choose a new password" />
      <ResetPasswordForm />
    </>
  );
}
