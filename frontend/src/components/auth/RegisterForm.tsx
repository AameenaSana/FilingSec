"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { authApi, PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";
import { validateNewPassword } from "./password";

export function RegisterForm() {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState<string | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const passwordError = validateNewPassword(password, confirm);
    if (passwordError) {
      setFieldErrors(passwordError);
      return;
    }
    setFieldErrors({});
    setPending(true);
    try {
      const ack = await authApi.register(email, password, displayName);
      setDone(ack.message);
    } catch (e) {
      setFieldErrors(e instanceof ApiError ? e.fieldErrors : {});
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  if (done) {
    return (
      <div className="flex flex-col gap-4">
        <Alert tone="success">{done}</Alert>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Open the link in the email to confirm your address, then sign in.
        </p>
        <Link href="/login" className="text-sm font-medium underline-offset-4 hover:underline">
          Go to sign in
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert tone="error">{error}</Alert>}
      <TextField
        label="Name"
        name="displayName"
        autoComplete="name"
        required
        maxLength={100}
        value={displayName}
        error={fieldErrors.displayName}
        onChange={(e) => setDisplayName(e.target.value)}
      />
      <TextField
        label="Email"
        type="email"
        name="email"
        autoComplete="email"
        required
        value={email}
        error={fieldErrors.email}
        onChange={(e) => setEmail(e.target.value)}
      />
      <TextField
        label="Password"
        type="password"
        name="password"
        autoComplete="new-password"
        required
        minLength={PASSWORD_MIN_LENGTH}
        maxLength={PASSWORD_MAX_LENGTH}
        hint={`At least ${PASSWORD_MIN_LENGTH} characters. A passphrase works well.`}
        value={password}
        error={fieldErrors.password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <TextField
        label="Confirm password"
        type="password"
        name="confirm"
        autoComplete="new-password"
        required
        value={confirm}
        error={fieldErrors.confirm}
        onChange={(e) => setConfirm(e.target.value)}
      />
      <Button type="submit" pending={pending}>
        {pending ? "Creating account…" : "Create account"}
      </Button>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-zinc-900 underline-offset-4 hover:underline dark:text-zinc-100">
          Sign in
        </Link>
      </p>
    </form>
  );
}
