"use client";

import { useState, type FormEvent } from "react";
import { validateNewPassword } from "@/components/auth/password";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { TextField } from "@/components/ui/TextField";
import { accountApi, PASSWORD_MIN_LENGTH } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";

export function ChangePasswordForm() {
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<{ tone: "success" | "error"; message: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResult(null);
    const invalid = validateNewPassword(password, confirm);
    if (invalid) {
      setFieldErrors(invalid);
      return;
    }
    setFieldErrors({});
    setPending(true);
    try {
      await accountApi.changePassword(current, password);
      setCurrent("");
      setPassword("");
      setConfirm("");
      setResult({ tone: "success", message: "Password changed. Your other sessions were signed out." });
    } catch (e) {
      if (e instanceof ApiError && e.code === "invalid-password") {
        setFieldErrors({ current: e.message });
      } else {
        setResult({ tone: "error", message: errorMessage(e) });
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <Card title="Password" description="Changing your password signs out all your other sessions.">
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {result && <Alert tone={result.tone}>{result.message}</Alert>}
        <TextField
          label="Current password"
          type="password"
          autoComplete="current-password"
          required
          value={current}
          error={fieldErrors.current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <TextField
          label="New password"
          type="password"
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
          autoComplete="new-password"
          required
          value={confirm}
          error={fieldErrors.confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <div>
          <Button type="submit" pending={pending}>
            Change password
          </Button>
        </div>
      </form>
    </Card>
  );
}
