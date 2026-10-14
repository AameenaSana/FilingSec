"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { TextField } from "@/components/ui/TextField";
import { accountApi } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/AuthProvider";

export function DeleteAccountForm() {
  const router = useRouter();
  const { clear } = useAuth();
  const [password, setPassword] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await accountApi.delete(password);
      clear();
      router.replace("/");
    } catch (e) {
      setError(e instanceof ApiError && e.code === "invalid-password" ? "Incorrect password." : errorMessage(e));
      setPending(false);
    }
  }

  return (
    <Card
      title="Delete account"
      description="Permanently deletes your account, watchlist, saved research and notifications. This cannot be undone."
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <TextField
          label="Confirm with your password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <label className="flex items-center gap-2 text-sm text-zinc-700 dark:text-zinc-300">
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />I understand
          this is permanent
        </label>
        <div>
          <Button type="submit" variant="danger" pending={pending} disabled={!confirmed || !password}>
            Delete my account
          </Button>
        </div>
      </form>
    </Card>
  );
}
