"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { authApi } from "@/lib/api/auth";
import { ApiError, errorMessage } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/AuthProvider";
import { safeNextPath } from "@/lib/auth/safe-next";
import { ResendVerification } from "./ResendVerification";

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setUser } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unverified, setUnverified] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    setUnverified(false);
    try {
      const user = await authApi.login(email, password);
      setUser(user);
      router.replace(safeNextPath(searchParams.get("next")));
    } catch (e) {
      setUnverified(e instanceof ApiError && e.code === "email-not-verified");
      setError(errorMessage(e));
      setPassword("");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" pending={pending}>
          {pending ? "Signing in…" : "Sign in"}
        </Button>
      </form>
      {unverified && <ResendVerification email={email} />}
      <div className="flex justify-between text-sm">
        <Link href="/forgot-password" className="text-zinc-600 underline-offset-4 hover:underline dark:text-zinc-400">
          Forgot password?
        </Link>
        <Link href="/register" className="font-medium underline-offset-4 hover:underline">
          Create an account
        </Link>
      </div>
    </div>
  );
}
