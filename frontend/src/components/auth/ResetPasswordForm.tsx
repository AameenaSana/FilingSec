"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { authApi, PASSWORD_MIN_LENGTH } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";
import { useLinkToken } from "./useLinkToken";
import { validateNewPassword } from "./password";

export function ResetPasswordForm() {
  const token = useLinkToken();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) {
      return;
    }
    const invalid = validateNewPassword(password, confirm);
    if (invalid) {
      setFieldErrors(invalid);
      return;
    }
    setFieldErrors({});
    setError(null);
    setPending(true);
    try {
      await authApi.resetPassword(token, password);
      setDone(true);
    } catch (e) {
      setFieldErrors(e instanceof ApiError && e.fieldErrors.newPassword ? { password: e.fieldErrors.newPassword } : {});
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  if (done) {
    return (
      <div className="flex flex-col gap-4">
        <Alert tone="success">Your password has been reset and all sessions were signed out.</Alert>
        <Link href="/login" className="text-sm font-medium underline-offset-4 hover:underline">
          Sign in with your new password
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      {token === null && <Alert tone="error">This reset link is incomplete. Request a new one.</Alert>}
      {error && <Alert tone="error">{error}</Alert>}
      <TextField
        label="New password"
        type="password"
        name="password"
        autoComplete="new-password"
        required
        hint={`At least ${PASSWORD_MIN_LENGTH} characters.`}
        value={password}
        error={fieldErrors.password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <TextField
        label="Confirm new password"
        type="password"
        name="confirm"
        autoComplete="new-password"
        required
        value={confirm}
        error={fieldErrors.confirm}
        onChange={(e) => setConfirm(e.target.value)}
      />
      <Button type="submit" pending={pending} disabled={!token}>
        Reset password
      </Button>
      <Link href="/forgot-password" className="text-sm underline-offset-4 hover:underline">
        Request a new link
      </Link>
    </form>
  );
}
