import type { Metadata } from "next";
import { RegisterForm } from "@/components/auth/RegisterForm";
import { AuthHeading } from "../AuthHeading";

export const metadata: Metadata = { title: "Create account · FilingLens" };

export default function RegisterPage() {
  return (
    <>
      <AuthHeading title="Create your account" subtitle="Track companies and research SEC filings." />
      <RegisterForm />
    </>
  );
}
