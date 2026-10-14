"use client";

import { useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { authApi } from "@/lib/api/auth";
import { errorMessage } from "@/lib/api/client";

/** Lets a user request a new verification email for the given address. */
export function ResendVerification({ email }: { email: string }) {
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<{ tone: "success" | "error"; message: string } | null>(null);

  async function resend() {
    setPending(true);
    try {
      const ack = await authApi.resendVerification(email);
      setResult({ tone: "success", message: ack.message });
    } catch (e) {
      setResult({ tone: "error", message: errorMessage(e) });
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {result ? (
        <Alert tone={result.tone}>{result.message}</Alert>
      ) : (
        <Button type="button" variant="secondary" pending={pending} onClick={resend} disabled={!email}>
          Resend verification email
        </Button>
      )}
    </div>
  );
}
