"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { authApi, type Account } from "@/lib/api/auth";
import { errorMessage } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/AuthProvider";
import { safeNextPath } from "@/lib/auth/safe-next";
import { supabase, supabaseConfigured, takeNext } from "@/lib/auth/supabase";

/**
 * Completes Google sign-in: exchanges the one-time code from Supabase for a
 * Supabase session, hands its access token to the backend for a FilingLens
 * session, then discards the Supabase session.
 */
export function SupabaseCallback() {
  const router = useRouter();
  const { setUser } = useAuth();
  const [error, setError] = useState<string | null>(null);
  // The code is single-use: never exchange it twice (e.g. StrictMode re-running effects).
  const request = useRef<Promise<Account> | null>(null);

  useEffect(() => {
    request.current ??= complete(new URLSearchParams(window.location.search));
    let cancelled = false;
    request.current
      .then((user) => {
        if (!cancelled) {
          setUser(user);
          router.replace(safeNextPath(takeNext()));
        }
      })
      .catch((e: unknown) => !cancelled && setError(e instanceof SignInError ? e.message : errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [router, setUser]);

  if (error === null) {
    return <Alert>Signing you in…</Alert>;
  }
  return (
    <div className="flex flex-col gap-4">
      <Alert tone="error">{error}</Alert>
      <Link href="/login" className="text-sm font-medium underline-offset-4 hover:underline">
        Back to sign in
      </Link>
    </div>
  );
}

/** A failure whose message is meant for the user. */
class SignInError extends Error {}

async function complete(params: URLSearchParams): Promise<Account> {
  if (!supabaseConfigured) {
    throw new SignInError("Google sign-in is not configured.");
  }
  // The URL can be crafted by anyone, so its error text is never shown.
  if (params.get("error")) {
    throw new SignInError("Google sign-in was cancelled or did not succeed. Please try again.");
  }
  const code = params.get("code");
  if (!code) {
    throw new SignInError("This sign-in link is incomplete. Please start again.");
  }
  const auth = supabase().auth;
  const { data, error } = await auth.exchangeCodeForSession(code);
  if (error || !data.session) {
    throw new SignInError("Google sign-in could not be completed. Please start again.");
  }
  try {
    return await authApi.supabaseSignIn(data.session.access_token);
  } finally {
    // FilingLens keeps its own session; nothing from Supabase stays in the browser.
    await auth.signOut({ scope: "local" });
  }
}
