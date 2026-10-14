"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { authApi } from "@/lib/api/auth";
import { errorMessage } from "@/lib/api/client";

export function ForgotPasswordForm() {
  const [email, setEmail] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<{ tone: "success" | "error"; message: string } | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    try {
      const ack = await authApi.forgotPassword(email);
      setResult({ tone: "success", message: ack.message });
    } catch (e) {
      setResult({ tone: "error", message: errorMessage(e) });
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      {result && <Alert tone={result.tone}>{result.message}</Alert>}
      <TextField
        label="Email"
        type="email"
        name="email"
        autoComplete="email"
        required
        value={email}
        onChange={(e) => setEmail(e.target.value)}
      />
      <Button type="submit" pending={pending}>
        Send reset link
      </Button>
      <Link href="/login" className="text-sm underline-offset-4 hover:underline">
        Back to sign in
      </Link>
    </form>
  );
}
