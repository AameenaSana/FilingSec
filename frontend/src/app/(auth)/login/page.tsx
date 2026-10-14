import type { Metadata } from "next";
import { Suspense } from "react";
import { LoginForm } from "@/components/auth/LoginForm";
import { AuthHeading } from "../AuthHeading";

export const metadata: Metadata = { title: "Sign in · FilingLens" };

export default function LoginPage() {
  return (
    <>
      <AuthHeading title="Sign in" />
      {/* LoginForm reads ?next=, which is only known in the browser. */}
      <Suspense>
        <LoginForm />
      </Suspense>
    </>
  );
}
