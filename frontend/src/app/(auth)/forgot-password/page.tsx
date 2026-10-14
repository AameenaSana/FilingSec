import type { Metadata } from "next";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";
import { AuthHeading } from "../AuthHeading";

export const metadata: Metadata = { title: "Reset password · FilingLens" };

export default function ForgotPasswordPage() {
  return (
    <>
      <AuthHeading title="Reset your password" subtitle="We will email you a link to choose a new password." />
      <ForgotPasswordForm />
    </>
  );
}
