"use client";

import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { TextField } from "@/components/ui/TextField";
import { accountApi, type Account } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/AuthProvider";

export function ProfileForm({ account }: { account: Account }) {
  const { setUser } = useAuth();
  const [displayName, setDisplayName] = useState(account.displayName);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<{ tone: "success" | "error"; message: string } | null>(null);
  const [fieldError, setFieldError] = useState<string | undefined>();

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setResult(null);
    setFieldError(undefined);
    try {
      setUser(await accountApi.update(displayName));
      setResult({ tone: "success", message: "Profile updated." });
    } catch (e) {
      setFieldError(e instanceof ApiError ? e.fieldErrors.displayName : undefined);
      setResult({ tone: "error", message: errorMessage(e) });
    } finally {
      setPending(false);
    }
  }

  return (
    <Card title="Profile">
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {result && <Alert tone={result.tone}>{result.message}</Alert>}
        <TextField label="Email" value={account.email} disabled readOnly />
        <TextField
          label="Name"
          name="displayName"
          required
          maxLength={100}
          value={displayName}
          error={fieldError}
          onChange={(e) => setDisplayName(e.target.value)}
        />
        <div>
          <Button type="submit" pending={pending} disabled={displayName.trim() === account.displayName}>
            Save
          </Button>
        </div>
      </form>
    </Card>
  );
}
